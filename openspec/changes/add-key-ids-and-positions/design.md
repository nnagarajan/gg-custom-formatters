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
- `JsonFormatter` exposes formatter options as setters (`setIncludePrimaryKeys`, ...), which GoldenGate binds from `gg.handler.<name>.format.<property>`.

rep3 today: `GROUPTRANSOPS 1000`, `MAXTRANSOPS` commented out, `IGNOREDELETES`, MAP of three `APPUSER` tables.

## Goals / Non-Goals

**Goals:**
- Exact per-operation `key_ids` and `pos`, index-aligned, so a consumer can match and deduplicate data messages.
- Correct `tx_complete` under `MAXTRANSOPS` + `GROUPTRANSOPS` without formatter state across deliveries.
- Fail fast and loudly on any operation whose key cannot be produced.
- No new dependencies; Java 8 source level; same artifact.

**Non-Goals:**
- Remembering state across deliveries or across Replicat restarts.
- Handling key changes (keys are immutable by contract).
- Reloading the key column file without a restart.

## Decisions

1. **Spike first; it gates the completeness signal.** Before implementing, run rep3 with `MAXTRANSOPS 2` and log per operation `getXidStr`, `getCsnStr`, `getPosition`, `getTxState`, `isLast`, and per delivery `getNextTranID`, `isTxEndTimeSet`, operation count. Also confirm: fragments carry the source xid/csn; key columns of a compressed update appear in the before image; a custom formatter setter is bound from `gg.handler.kafkahandler.format.keyColumnsFile`; an exception thrown from `formatOp` abends Replicat; and what `TableName` returns for the mapped tables. If no signal reliably identifies a source transaction's last operation, stop and revisit the `tx_complete` requirement before coding it.
   *Update (implementation):* the user chose to skip the spike. Its checks remain open risks (see Risks).

2. **Only the delivery's last group can be incomplete.** Splits happen at delivery boundaries, so within one delivery every group except the last ends before the next starts: those markers get `tx_complete = true` without any API. The last group's flag is `isLast()` of the delivery's last operation (user decision). In GG 21.4 bytecode, `isLast()` returns `txState == END || txState == WHOLE`; it is the supported accessor for that field, while `getTxState()` is `@Deprecated`. *Alternative:* remember the previous delivery's last `xid:csn` — rejected: state is lost on restart and it cannot tell whether the *last* group is complete.

3. **Collect per operation inside the existing group accumulator.** Per table, the accumulator keeps count, key column names, a list of key value arrays and a list of positions, appended together in trail order, so `key_ids`, `pos` and `event_count` cannot drift apart. *Alternative:* one `events` array of `{pos, key}` objects — rejected by the user in favor of parallel arrays.

4. **Key resolution in a small separate class, cached per table.** On first sight of a table (by upper-cased `SCHEMA.TABLE`), resolve key columns: key column file entry, else `getKeyColumns()` when not `isPseudoKey()`, else abend. Validate that each mapped column exists (abend) and warn on nullable mapped columns, then cache the resolved column indexes. Clear the cache in `metaDataChanged` so DDL re-validates. *Alternative:* resolve on every operation — rejected, repeated validation and log spam.

5. **Key value extraction.** For each key column index: deletes read the before value; other operations read the after value if present, else the before value. Absent in the image(s) read → abend "missing"; `isValueNull()` → abend "NULL"; otherwise `getValue()` as a string. Primary-key-update operation types are treated as updates. `DO_TRUNCATE` abends before any key lookup.

6. **Abend = throw before writing.** All operations of the delivery are validated while building markers, before `writeJson`, so a failing delivery writes zero bytes. Errors are `IllegalStateException` with a message carrying table, column, operation type, `pos`, xid and csn, and logged at ERROR.

7. **Key column file as `.properties`, bound by a setter.** `setKeyColumnsFile(String path)` loads it with `java.util.Properties`; keys are upper-cased `SCHEMA.TABLE`, values comma-separated column names (trimmed, upper-cased). A missing file, empty value or empty column name throws, so Replicat fails at startup. *Alternatives:* YAML/JSON (new dependency), Replicat `KEYCOLS` alone (also changes Replicat's own key; kept as an operational option, not required).

8. **Field names and order.** Add `TX_COMPLETE("tx_complete")`, `KEY_COLUMNS("key_columns")`, `KEY_IDS("key_ids")`, `POS("pos")` to `TransactionMetaDataType`. Marker order: `xid, csn, tx_ts, event_count, tx_complete, data_collections`; entry order: `data_collection, event_count, key_columns, key_ids, pos`. The single-group form keeps the delivery's xid/csn/begin time.

## Risks / Trade-offs

- [Consumer assumes one complete marker per `xid:csn`] → Deploy the jar with `MAXTRANSOPS` still unset (new fields are additive); set `MAXTRANSOPS` only after the Spark consumer merges by `xid:csn` until `tx_complete` and dedupes by `pos`.
- [Spike skipped: unverified that Replicat sets `END`/`WHOLE` per operation; if it leaves `UNKNOWN`, `isLast()` is false and every marker says `tx_complete` false; also unverified that a `formatOp` exception abends Replicat and that `format.keyColumnsFile` binds to the setter] → Verify on rep3 before enabling `MAXTRANSOPS` (task 6.2 checks `tx_complete` true on a whole transaction and false, false, true on a split one); keys and positions do not depend on it.
- [Replay after restart re-sends a fragment] → `pos` makes deduplication exact; documented for consumers.
- [Abend loops on a bad record already in the trail] → README recovery steps: fix or remove the table's key file entry and restart, or skip the transaction, or re-capture after fixing supplemental logging.
- [Missing supplemental logging for mapped columns] → README requires `ADD TRANDATA ... COLS (...)` and `LOGALLSUPCOLS`; the abend surfaces it immediately.
- [Message size] → ~35–80 B per operation; bounded by `MAXTRANSOPS` (e.g. 1000 → ~40–80 KB) plus grouped small transactions (~400 KB at `GROUPTRANSOPS 1000`), under the ~1 MB default.
- [Binary/RAW key values] → Written as whatever string `getValue()` returns; documented, not normalized.

## Migration Plan

1. `mvn clean package`; copy the jar to `/opt/oracle/ggbd/ggjava/resources/customlib/`; optionally add `gg.handler.kafkahandler.format.keyColumnsFile=dirprm/key_columns.properties`; restart rep3 with `MAXTRANSOPS` still unset. Markers gain new fields only.
2. After the consumer handles fragments, set `MAXTRANSOPS` in rep3 and restart.
3. Rollback: unset `MAXTRANSOPS`, restore the previous jar, remove the property, restart rep3.

## Open Questions

- Production value of `MAXTRANSOPS` (sizing only; any value works with this design).
