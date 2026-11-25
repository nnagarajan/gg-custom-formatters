# Tasks

## 1. Prerequisites

- [x] 1.1 Archive the completed change `one-marker-per-source-transaction` (`openspec archive one-marker-per-source-transaction`) and verify `openspec/specs/transaction-boundary-markers/spec.md` exists and `openspec validate add-key-ids-and-positions` resolves the MODIFIED requirements against it.

## 2. Spike: GoldenGate behavior under MAXTRANSOPS (manual, in the GoldenGate environment)

_Skipped by user decision. Most of its questions were answered on rep3 on 2026-11-23 with the real formatter; see design.md "Observed on rep3". Left unchecked because the debug formatter was never built._

- [ ] 2.1 Build a throwaway debug formatter (not committed) that logs, per operation, `getXidStr`, `getCsnStr`, `getPosition`, `getTxState`, `isLast`, operation type, `TableName.getSchemaName/getShortName/getOriginalName`, and for each key column `hasBeforeValue/hasAfterValue/isMissing/isValueNull`; and per delivery `getXidStr`, `getCsnStr`, `getNextTranID`, `isTxEndTimeSet`, operation count. Add a setter `setKeyColumnsFile` that logs its argument and throws when the argument is `"throw"`. Verify it loads in rep3 by its log lines.
- [ ] 2.2 Run rep3 with `MAXTRANSOPS 2`, `GROUPTRANSOPS 1000` and `gg.handler.kafkahandler.format.keyColumnsFile=dirprm/x.properties`; on the source run T1 = 5 inserts on ORDER_LINE_ITEMS, T2 = 1 insert, T3 = 1 insert, then T4 = an update on ORDERS that does not touch ORDER_ID. Verify from the log: fragments carry T1's source xid/csn; which signal (`TxState`, `isLast`, `getNextTranID`) marks T1's last operation and T2/T3 as complete; that T4's key is in the before image; that the setter receives the property value.
- [ ] 2.3 Set the property to `throw`, restart rep3, and verify Replicat abends (record the report file error). Then make the debug formatter throw from `formatOp` and verify Replicat abends and restarts at the same position.
- [ ] 2.4 Record the findings and the chosen completeness signal in design.md (Decisions 1–2, `TableName` form used for matching). If no signal reliably marks a source transaction's last operation, stop and revisit the `tx_complete` requirement with the user before group 5; groups 3–4 may proceed.

## 3. Keys from metadata and positions

- [x] 3.1 Add `KEY_COLUMNS`, `KEY_IDS`, `POS` to `TransactionMetaDataType` and extend the test helpers in `TransactionBoundaryEventJsonFormatterTest` to mock `getPosition`, `getOperationType`, `getTableMetaData` (key columns, `isPseudoKey`, column metadata with index and nullability) and `DsColumn` before/after values. Verify with `mvn test-compile`.
- [x] 3.2 Write failing tests for the spec scenarios "Primary key from metadata", "Composite key", "Single-column key is wrapped", "Update without the key in the after image", "Delete uses the before image", "Same row changed twice", "Positions align with keys", "Array lengths equal the count" and the updated "Grouped marker fields" (without `tx_complete` yet). Verify they fail with `mvn test` against the current formatter.
- [x] 3.3 Implement per design.md Decisions 3–5 and 8: per-table key column resolution from metadata with a cache cleared in `metaDataChanged`, key value extraction (after then before; delete before), and parallel `key_ids`/`pos` lists in the accumulator, written in the specified field order. Verify all tests pass with `mvn test`, including the 8 existing ones.
- [x] 3.4 Update README "Message Format" with `key_columns`, `key_ids`, `pos` (example with a composite key and real 20-digit positions), the per-operation size (~35–80 B) and the dedupe-by-`pos` guidance. Verify every JSON example parses with `python3 -m json.tool`.

## 4. Key column file and abends

- [x] 4.1 Write failing tests for "Key column file overrides the primary key", "File maps a table" (case-insensitive), "No file configured", "Unreadable file", "Compressed update without a mapped column", "NULL in a mapped business key", "Truncate reaches the formatter", "Mapped column does not exist", "Table without primary key or mapping", "Nullable business key column" (assert the WARN via the logger or a test hook), and that a failing delivery writes zero bytes. Verify they fail with `mvn test`.
- [x] 4.2 Implement per design.md Decisions 4–7: `setKeyColumnsFile` loading a `.properties` file (upper-cased `SCHEMA.TABLE` keys, trimmed column lists, throw on missing file or malformed entry), mapped-column existence check and nullable warning on first sight, and `IllegalStateException` abends for missing, NULL, truncate and unresolvable keys with table, column, operation type, `pos`, xid and csn in the message, raised before any output is written. Verify all tests pass with `mvn test`.
- [x] 4.3 Update README: the `keyColumnsFile` property and file format with an example for the three APPUSER tables; the abend rules with the exact error wording; recovery steps for a bad record already in the trail (fix/remove the mapping and restart, `SKIPTRANSACTION`, or re-capture); supplemental logging (`ADD TRANDATA ... COLS (...)`, `LOGALLSUPCOLS`) and that Extract must not capture truncates. Verify the example file matches the format the tests load.

## 5. Completeness flag and MAXTRANSOPS

- [x] 5.1 Add `TX_COMPLETE` to `TransactionMetaDataType` and extend test helpers to mock the completeness signal chosen in 2.4. Write failing tests for "Source transaction split across deliveries", "Fragment counts only its own operations", "Whole transaction in one delivery", "Split transaction", "Split transaction grouped with others", the updated "Single source transaction is one JSON object" and "Grouped marker fields" (now with `tx_complete`). Verify they fail with `mvn test`.
- [x] 5.2 Implement per design.md Decision 2: every group except the delivery's last gets `tx_complete` true; the last group's value comes from the chosen signal; write `tx_complete` after `event_count`. Update the class Javadoc to describe fragments and `tx_complete`. Verify all tests pass with `mvn test`.
- [x] 5.3 Update README: replace the "`MAXTRANSOPS` must stay unset" rule with `MAXTRANSOPS` support, fragment semantics (`event_count` per delivery, merge by `xid:csn` until `tx_complete` true, dedupe by `pos` after restarts), the size bound per `MAXTRANSOPS`, and the rollout order (deploy jar first, enable `MAXTRANSOPS` only after the consumer handles fragments). Verify the replicat example and JSON examples are valid.

## 7. Selectable marker fields

- [x] 7.1 Write tests for "Positions only", "Keys only", "Both off", "Keys off skips key checks", "Keys off still rejects truncates", "Invalid flag value", plus defaults and case-insensitive values.
- [x] 7.2 Implement `setMarkerKeys` / `setMarkerPos` per design.md Decision 9; move the truncate check to `KeyColumnResolver.checkSupported` so it runs with keys off. Verify all tests pass with `mvn test`.
- [x] 7.3 Update README: the two properties in the `rep3.props` example, a Marker Fields section with the field combinations, and the abend table. Note in the sample key column file that it applies only with `markerKeys=true`.
- [x] 7.4 Read the key column file only when `markerKeys=true`: `setKeyColumnsFile` stores the path, `init` loads it (fallback on first delivery). Tests: keys off with a missing file starts and formats in either property order; missing/malformed file fails in `init`; fallback load when `init` is not called.

## 6. Integration check

- [x] 6.1 Run `mvn clean package` and verify all tests pass and `target/gg-custom-formatters-1.0-SNAPSHOT.jar` is produced with no `pom.xml` changes; run `openspec validate add-key-ids-and-positions` and verify it passes.
- [ ] 6.2 Deploy to rep3 with `MAXTRANSOPS` unset and the key column file configured; run one multi-table transaction and verify the Kafka message has `key_columns`, `key_ids`, `pos` matching the data messages' `pos`, and `tx_complete` true. Then (after the consumer is ready) set `MAXTRANSOPS 2`, run a 5-operation transaction and verify three markers with `tx_complete` false, false, true whose `pos` lists together cover all 5 operations once. Also verify the rep3 log shows `Loaded key column file ...` at startup (confirms `init`), and that `markerKeys=false` (with `keyColumnsFile` still set) drops `key_columns`/`key_ids` and logs that the file is not read.
- [ ] 6.3 With `markerKeys=true` and `markerPos=true`, run a 550-operation transaction on rep3 without any abend and verify 275 markers of 2 operations, `tx_complete` false … true, whose `pos` lists cover all 550 operations once. (Done on 2026-11-23 with both flags false; the keys-on run abended on the first fragment because of a wrong key file entry.)
- [ ] 6.4 Enable formatter logging on rep3 (`-Dlog4j.configurationFile` with a `com.technext` logger, see README) and verify `Loaded key column file ...` appears at startup, confirming `init` is called.

## 8. Caveats and operational follow-ups

- [x] 8.1 Document the caveats in README: Caveats at a glance, Kafka Producer Settings (fixed key or 1-partition topic, `kp.properties`), Consumer Guide (merge by `xid:csn`, deduplicate by `pos`, replay overlap), Capacity Planning (measured sizes, daily volume, bulk transactions), the research result that no GoldenGate parameter applies `MAXTRANSOPS` to a replay, and the `VERSION`-in-key caveat.
- [ ] 8.2 On rep3: set `gg.handler.kafkahandler.keyMappingTemplate` to a fixed value (or recreate the topic with 1 partition) and apply the recommended `kp.properties`; verify markers show a non-null key and one partition.
- [ ] 8.3 On rep3: start a large transaction with `MAXTRANSOPS 2`, `KILL REPLICAT rep3` after several fragments are published, restart, and record whether Replicat resumes in the middle (and splits the rest) or replays the whole transaction. Update README and design Decision 11.
- [ ] 8.4 Measure the transaction size distribution (average, p99, max) from the topic with the README `jq` command, including the largest bulk affirm/confirm; set `MAXTRANSOPS` and the Kafka size limits from it.
- [ ] 8.5 Ask the trading application team whether bulk affirm/confirm must be one database transaction or can commit in chunks.
- [ ] 8.6 Consumer team: implement fragment merging and `pos` deduplication per the README Consumer Guide, including the whole-transaction replay case, before `MAXTRANSOPS` is enabled in production.
- [ ] 8.7 Decide whether a hard marker size bound is required; if so, propose a custom handler change (design Decision 11). Not needed for the recommended counts-only setup (design Decision 12).

_Tasks 8.3–8.7 apply only to deployments that use keys, positions or `MAXTRANSOPS`._

## 9. Recommended setup: counts only, no MAXTRANSOPS

- [x] 9.1 Document the recommended setup in README (settings, measured size, consumer rules, limits), add a column to Caveats at a glance saying which caveats apply to it, add caveats 10 (rep3 and data Replicat must deliver the same operations) and 11 (rep3 down stops markers silently), remove `IGNOREDELETES` from the example Replicat parameters, set the example `rep3.props` to the recommended flags, and add a counts-only section to the Consumer Guide. Verify every JSON example parses and every internal link resolves.
- [x] 9.2 Record the decision in design.md (Decision 12), proposal.md and the spec (scenario "Counts-only marker does not grow with the transaction"). Verify with `openspec validate add-key-ids-and-positions`.
- [ ] 9.3 Add a unit test for the scenario "Counts-only marker does not grow with the transaction" (54,000 operations over three tables, both flags off). Verify with `mvn test`.
- [ ] 9.4 On rep3: set `markerKeys=false` and `markerPos=false` (exact names), remove `MAXTRANSOPS 250` from `dirprm/rep3.prm`, confirm rep3 has no `IGNOREDELETES`/filters the data Replicat `kc` lacks, restart, and verify a marker has only `data_collection` and `event_count` per table and `tx_complete` true. Also record what rep3 does with a misspelt property such as `format.markerOps`.
- [ ] 9.5 Consumer team: add a marker freshness or rep3 lag alert (caveat 11) next to the stuck-transaction alert.
