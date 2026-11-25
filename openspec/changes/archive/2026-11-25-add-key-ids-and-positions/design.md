# Design

## Context

See proposal.md (Why). The formatter groups a delivery's operations by their own `xid:csn` and writes once, on the delivery's last operation (`gg.handler.kafkahandler.mode=tx`, one Kafka message per delivery). This change keeps that structure and adds per-operation data to each group.

Facts from the GoldenGate for Big Data 21.4 jars (`/opt/oracle/ggbd/ggjava/resources/lib`), checked with `javap`; *behavior* under `MAXTRANSOPS` is not yet verified:

- `DsOperation`: `getPosition()` (20-digit seqno + RBA), `getPositionSeqno()`, `getPositionRba()`, `getTableMetaData()`, `getColumns()` / `getColumn(int)`, `getOperationType()`, `getTxState()` (`BEGIN`, `MIDDLE`, `END`, `WHOLE`, `UNKNOWN`), `isLast()`.
- `DsTransaction`: `getNextTranID()`, `isTxEndTimeSet()`.
- `DsColumn`: `getBefore()`, `getAfter()`, `hasBeforeValue()`, `hasAfterValue()`, `isMissing()`, `isValueNull()`, `getValue()`.
- `TableMetaData`: `getKeyColumns()` (key order), `isPseudoKey()`, `getColumnMetaData(...)`; `ColumnMetaData`: `getIndex()`, `getColumnName()`, `isNullable()`.
- `TableName`: `getSchemaName()`, `getShortName()`, `getOriginalName()`.
- `OpType` includes `DO_TRUNCATE`, `DO_UPDATE_FIELDCOMP_PK`, `DO_UNIFIED_PK_UPDATE_VAL`.
- `JsonFormatter` exposes formatter options as setters (`setIncludePrimaryKeys`, `setIncludePosition`, ...), which GoldenGate binds from `gg.handler.<name>.format.<property>`.
- `AbstractNgFormatter.init(DsConfiguration, DsMetaData)` (from `NgFormatter`) is the startup hook; it returns at once unless `metaColumnsTemplate` is set, so overriding it and calling `super.init` is safe.

rep3 when this change started: `GROUPTRANSOPS 1000`, `MAXTRANSOPS` commented out, MAP of three `APPUSER` tables. (`IGNOREDELETES` appeared in the README example, not in the deployed rep3; it is removed from the example by Decision 12.) On 2026-11-24 the deployed rep3 had `MAXTRANSOPS 250` from testing; the recommended setup removes it.

## Goals / Non-Goals

**Goals:**
- Exact per-operation `key_ids` and `pos`, index-aligned, so a consumer can match and deduplicate data messages.
- Correct `tx_complete` under `MAXTRANSOPS` + `GROUPTRANSOPS` without formatter state across deliveries.
- Fail fast and loudly on any operation whose key cannot be produced.
- Keys and positions selectable per deployment, with unused work (key resolution, key file reading) skipped.
- No new dependencies; Java 8 source level; same artifact.

**Non-Goals:**
- Remembering state across deliveries or across Replicat restarts.
- Handling primary key changes (immutable by contract). Mapped columns that change on update, such as `VERSION`, are reported with their after-image value and are not tracked as key changes.
- Bounding marker size on a replay after an abend (needs a custom handler; see Decision 11).
- Reloading the key column file without a restart.

## Decisions

1. **Spike first; it gates the completeness signal.** Before implementing, run rep3 with `MAXTRANSOPS 2` and log per operation `getXidStr`, `getCsnStr`, `getPosition`, `getTxState`, `isLast`, and per delivery `getNextTranID`, `isTxEndTimeSet`, operation count. Also confirm: fragments carry the source xid/csn; key columns of a compressed update appear in the before image; a custom formatter setter is bound from `gg.handler.kafkahandler.format.keyColumnsFile` (and `init` is called after the setters); an exception thrown from `formatOp` abends Replicat; and what `TableName` returns for the mapped tables. If no signal reliably identifies a source transaction's last operation, stop and revisit the `tx_complete` requirement before coding it.
   *Update (implementation):* the user chose to skip the spike. Its checks remain open risks (see Risks).
   *Update (rep3, 2026-11-23):* most spike questions were answered by running the deployed formatter (see "Observed on rep3" below).

2. **Only the delivery's last group can be incomplete.** Splits happen at delivery boundaries, so within one delivery every group except the last ends before the next starts: those markers get `tx_complete = true` without any API. The last group's flag is `isLast()` of the delivery's last operation (user decision). In GG 21.4 bytecode, `isLast()` returns `txState == END || txState == WHOLE`; it is the supported accessor for that field, while `getTxState()` is `@Deprecated`. *Alternative:* remember the previous delivery's last `xid:csn` — rejected: state is lost on restart and it cannot tell whether the *last* group is complete.

3. **Collect per operation inside the existing group accumulator.** Per table, the accumulator keeps count, key column names, a list of key value arrays and a list of positions, appended together in trail order, so `key_ids`, `pos` and `event_count` cannot drift apart. *Alternative:* one `events` array of `{pos, key}` objects — rejected by the user in favor of parallel arrays.

4. **Key resolution in a small separate class, cached per table.** On first sight of a table (by upper-cased `SCHEMA.TABLE`), resolve key columns: key column file entry, else `getKeyColumns()` when not `isPseudoKey()`, else abend. Validate that each mapped column exists (abend) and warn on nullable mapped columns, then cache the resolved column indexes. Clear the cache in `metaDataChanged` so DDL re-validates. *Alternative:* resolve on every operation — rejected, repeated validation and log spam.

5. **Key value extraction.** For each key column index: deletes read the before value; other operations read the after value if present, else the before value. Absent in the image(s) read → abend "missing"; `isValueNull()` → abend "NULL"; otherwise `getValue()` as a string. Primary-key-update operation types are treated as updates. `DO_TRUNCATE` abends before any key lookup.

6. **Abend = throw before writing.** All operations of the delivery are validated while building markers, before `writeJson`, so a failing delivery writes zero bytes. Errors are `IllegalStateException` with a message carrying table, column, operation type, `pos`, xid and csn, and logged at ERROR.

7. **Key column file as `.properties`, bound by a setter.** `setKeyColumnsFile(String path)` only stores the path; the file is loaded in `init(DsConfiguration, DsMetaData)` (called after all properties are set, so the order of `keyColumnsFile` and `markerKeys` does not matter) and only when `markerKeys` is true, with a fallback load on the first delivery in case `init` is not called. It is parsed line by line; keys are upper-cased `SCHEMA.TABLE`, values comma-separated column names (trimmed, upper-cased). A missing file, empty value or empty column name throws, so Replicat fails at startup. *Alternatives:* YAML/JSON (new dependency), Replicat `KEYCOLS` alone (also changes Replicat's own key; kept as an operational option, not required).

8. **Field names and order.** Add `TX_COMPLETE("tx_complete")`, `KEY_COLUMNS("key_columns")`, `KEY_IDS("key_ids")`, `POS("pos")` to `TransactionMetaDataType`. Marker order: `xid, csn, tx_ts, event_count, tx_complete, data_collections`; entry order: `data_collection, event_count, key_columns, key_ids, pos`. The single-group form keeps the delivery's xid/csn/begin time.

9. **Marker field flags.** `setMarkerKeys(String)` and `setMarkerPos(String)` bound from `format.markerKeys` / `format.markerPos`, default `true`, parsed strictly (`true`/`false`, case-insensitive, trimmed; anything else throws at startup). Names avoid `JsonFormatter`'s own `setIncludePosition` / `setIncludePrimaryKeys`, which control its data-message output. Turned-off fields are omitted, not written empty. With keys off the formatter skips `KeyColumnResolver.resolve`/`values` entirely but still calls the truncate check. Positions are still collected internally because they carry the per-table count. With keys off the key column file is not read (Decision 7). Without `pos` there is no exact dedupe of re-sent fragments, so `MAXTRANSOPS` deployments should keep `markerPos` true (documented, not enforced). *Alternative:* one list property (`markerFields=keys,pos`) — rejected, two booleans are simpler to read and validate.

10. **Kafka delivery is configured, not coded.** Trail order on the topic depends on all markers going to one partition, which the formatter cannot control: the Kafka handler sets the key from `keyMappingTemplate` (unset on rep3, so keys were `null`). README prescribes a fixed key and/or a 1-partition topic and producer settings (idempotence, `acks=all`, ≤ 5 in flight, `max.request.size` above the largest uncompressed marker). Consumers rely on this for the `pos` high-water-mark deduplication.

11. **Replay after an abend is accepted, not prevented.** Researched: no Replicat parameter makes `MAXTRANSOPS` apply on recovery; `REPERROR` covers database apply errors, not Java handler exceptions; Oracle documents source-boundary replay for `GROUPTRANSOPS`/`BATCHSQL` errors. Splitting in the formatter was considered and rejected: in `mode=tx` the Kafka handler joins all formatter output of a delivery into one message (bytecode of `KafkaHandler.transactionCommit`); in `mode=op` the handler sends nothing for an operation with no output (`NgBAOSFormattedData.size()` is 0), but it never calls the formatter at commit, so operations held back would be lost if Replicat crashed after checkpointing them. *Alternative for a hard size bound:* a custom handler (`gg.handler.<name>.type=<class>`) that splits each delivery into markers of at most N operations and sends and flushes them before returning from `transactionCommit`; deferred until a hard bound is required.

12. **Recommended deployment: counts only, no `MAXTRANSOPS`.** Decided after the size, replay and consumer analysis (2026-11-24). With `markerKeys=false` and `markerPos=false` a marker is about 300 bytes for three tables whatever the operation count (measured: 296 bytes for 5 operations, 312 for 54,000), so `MAXTRANSOPS` has nothing to bound, and leaving it unset removes fragments, the whole-transaction replay overlap (Decision 11) and the need for `pos` to deduplicate them: each `xid:csn` has exactly one marker, and a repeated one is a duplicate. Without key resolution the key-related abends and the key column file drop out. The consumer completes a transaction by counting distinct data messages per table (deduplicated by the data messages' own `pos`) against `event_count`, which the Spark consumer already does. The defaults stay `true` so the formatter's behavior does not change for existing deployments; the recommendation is documented, not enforced. *Alternatives considered:* keys with `MAXTRANSOPS` and a formatter-side key cap (`markerMaxKeys` with a `keys_omitted` flag) or a per-delivery `fragment` number; not built, because the counts-only setup removes the problem they address. Two operational requirements apply to every setup and are documented as caveats: rep3 and the data Replicat must deliver the same operations (a filter such as `IGNOREDELETES` on only one makes counts disagree, and a consumer waiting for them never completes); and while rep3 is down no markers arrive, so consumers need a marker freshness or Replicat lag alert in addition to a stuck-transaction alert.

## Risks / Trade-offs

- [Consumer assumes one complete marker per `xid:csn`] → Deploy the jar with `MAXTRANSOPS` still unset (new fields are additive); set `MAXTRANSOPS` only after the Spark consumer merges by `xid:csn` until `tx_complete` and dedupes by `pos`.
- [Still unverified: `init` called after the setters; `TableName` form for mapped tables beyond the three APPUSER tables in use] → the key file fallback load covers a missing `init`; the three APPUSER tables matched their file entries on rep3. (Verified on rep3 and no longer risks: `isLast()` reflects `MIDDLE`/`END`/`WHOLE`; a `formatOp` exception abends Replicat; `format.*` properties bind to the setters.)
- [Abend during a split transaction: Replicat replays it as one `WHOLE` delivery, ignoring `MAXTRANSOPS`] → the replay marker is unbounded by `MAXTRANSOPS` and overlaps fragments already published. README: size Kafka limits for the largest source transaction; consumers deduplicate by `pos`. No formatter change: it formats the replay correctly as a whole transaction (`tx_complete` true).
- [Replay after restart re-sends a fragment] → `pos` makes deduplication exact; documented for consumers. With `markerPos=false` and `MAXTRANSOPS` it is not possible → README says keep `markerPos` true with `MAXTRANSOPS`.
- [`init` not called by GoldenGate] → the key column file is loaded on the first delivery instead; a bad file then abends on the first transaction rather than at startup. The "Loaded key column file" log line confirms which.
- [Flags changed between deployments] → the marker shape changes; README says update consumers first.
- [Abend loops on a bad record already in the trail] → README recovery steps: fix or remove the table's key file entry and restart, or skip the transaction, or re-capture after fixing supplemental logging.
- [Missing supplemental logging for mapped columns] → README requires `ADD TRANDATA ... COLS (...)` and `LOGALLSUPCOLS`; the abend surfaces it immediately.
- [Message size] → ~35–80 B per operation; bounded by `MAXTRANSOPS` in normal running, not on replay after an abend (e.g. 1000 → ~40–80 KB) plus grouped small transactions (~400 KB at `GROUPTRANSOPS 1000`), under the ~1 MB default.
- [Binary/RAW key values] → Written as whatever string `getValue()` returns; documented, not normalized.
- [Markers spread over partitions (null key)] → Consumers could see fragments out of order and the `pos` watermark would drop operations. README requires a fixed `keyMappingTemplate` or a 1-partition topic.
- [Bulk actions (trade affirm/confirm) are single large transactions] → 50,000 trades ≈ 150,000 operations ≈ 6–12 MB as one marker on replay. `MAXTRANSOPS` 5,000–10,000 for normal running, Kafka limits sized for the largest bulk, and ask the application team whether bulk actions can commit in chunks.
- [`max.request.size` is checked uncompressed] → Compression does not help the producer limit; README sizes it from measured marker sizes.
- [Mapped `VERSION`-style column changes on update] → `key_ids` holds the new value; supplemental logging must include it or updates that do not set it abend as MISSING. Documented in README Key Columns.
- [rep3 and the data Replicat deliver different operations (filters, `IGNOREDELETES`, different MAPs, repositioned separately)] → counts never match and a consumer waiting for them stalls. README caveat 10 and Replicat Settings; the example parameters no longer use `IGNOREDELETES`.
- [rep3 down] → no markers, so a consumer's stuck-transaction alert never fires. README caveat 11: monitor rep3 or marker freshness.
- [Misspelt flag property, for example `markerOps`] → the field stays on. README says to check the first marker after a restart. Whether GoldenGate warns about an unknown `format.*` property is not verified.
- [Formatter INFO logs invisible with default GoldenGate logging] → README shows a `com.technext` logger for a custom log4j2 file.

## Observed on rep3 (2026-11-23)

GoldenGate for Big Data 21.4, `GROUPTRANSOPS 1000`, `MAXTRANSOPS 2`, three APPUSER tables. Evidence: `dirrpt/REP3.log` handler events and `REP3*.rpt`.

| Question | Result |
|---|---|
| Does `MAXTRANSOPS` split a source transaction? | Yes. 550 operations → 275 commits of `ops=2`, `state=MIDDLE`, last `END`. Markers `tx_complete` false … true. |
| Do fragments carry the source xid/csn? | Yes, every fragment marker has the same `xid:csn`. |
| Is `isLast()` usable (Decision 2)? | Yes; handler state is `MIDDLE` for inner fragments, `END` for the last, `WHOLE` for unsplit transactions. |
| Does an exception from `formatOp` abend Replicat? | Yes (`OGG-15051`, `OGG-01668`); nothing published for that delivery. Seen with the "no column DETAIL_ID" key file error. |
| Are `format.*` properties bound to the setters? | Yes (`Setting property on '...TransactionBoundaryEventJsonFormatter'`); an invalid `markerKeys` value abended at startup. |
| What happens on restart after an abend inside a split transaction? | Replicat replays the interrupted transaction as one delivery (`ops=550`, `state=WHOLE`); `MAXTRANSOPS` is not applied. |
| Do formatter INFO logs appear? | No. Default `log4j-default.properties` routes only `oracle.goldengate` to `REP3.log`; other loggers go to the report at ERROR. |

## Migration Plan

1. `mvn clean package`; copy the jar to `/opt/oracle/ggbd/ggjava/resources/customlib/`; optionally add `gg.handler.kafkahandler.format.keyColumnsFile=dirprm/key_columns.properties` and set `markerKeys` / `markerPos` (default both true); restart rep3 with `MAXTRANSOPS` still unset. Markers gain new fields only.
2. Recommended: set `markerKeys=false` and `markerPos=false`, remove any `MAXTRANSOPS` line, restart rep3 (Decision 12).
3. Only for deployments that need fragments: after the consumer handles them, set `MAXTRANSOPS` in rep3 (with `markerPos=true`) and restart.
4. Rollback: unset `MAXTRANSOPS`, restore the previous jar, remove the formatter properties (`keyColumnsFile`, `markerKeys`, `markerPos`), restart rep3.

## Open Questions

- How does GoldenGate 21.4 treat an unknown `format.*` property (ignore, warn or abend)? (task 9.4)

- Production value of `MAXTRANSOPS` (sizing only; any value works with this design). README suggests 5,000–10,000.
- After an abend with some fragments already committed, does Replicat resume in the middle of the transaction (and split the rest) or replay it whole? (task 8.3)
- Largest bulk transaction to expect, and can bulk affirm/confirm commit in chunks? (tasks 8.4, 8.5)
- Is a hard bound on marker size required (custom handler, Decision 11)? (task 8.7)
