# Tasks

## 1. Failing tests for the marker contract

- [x] 1.1 Rewrite `src/test/java/com/technext/goldengate/dasource/formatter/TransactionBoundaryEventJsonFormatterTest.java` with helpers that mock `DsOperation` (`getXidStr`, `getCsnStr`, `getTableName`, incrementing `getOperationSeqno`, `getTimestamp`) and `DsTransaction` (`getXidStr`, `getCsnStr`, `getTransactionBeginTime`, `getTotalOps`, `getOperations`, `getLastOperation`), and drive the formatter like the Kafka handler in `mode=tx`: `formatOp` for every operation into one `NgBAOSFormattedData`, then parse `getByteArrayOutputStream(0)` with `Json.createReader(...).read()`. Verify it compiles with `mvn test-compile`.
- [x] 1.2 Add the 8 tests: `singleSourceTransactionIsOneJsonObject` (existing assertions: xid, csn, tx_ts, event_count 2, TABLE1 = 2), `singleTransactionCountsEachTable`, `groupedSourceTransactionsProduceOneMarkerEach` (X1:500 / X2:600 / X3:700, delivery ids X1:500), `groupedMarkersCarryEachTransactionsOwnTimestamp`, `sameCsnDifferentXidAreSeparateMarkers`, `markerCountsAddUpToTheDeliveredOperations` (50 transactions of 1–4 ops), `operationWithoutOwnIdsFallsBackToTheDeliveredTransaction`, `onlyTheLastOperationWritesOutput`. Verify with `mvn test` against the unchanged formatter that tests 3–6 fail (record the output) and that 1, 2 and 8 pass.

## 2. Formatter: one marker per source transaction

- [x] 2.1 In `TransactionBoundaryEventJsonFormatter`, replace `formatTransaction` with marker building per design.md: keep the last-operation guard in `formatOp`; group `dsTransaction.getOperations()` in a `LinkedHashMap` keyed `xid + ":" + csn` from each operation's own ids (null → the `DsTransaction`'s value); per group, a private accumulator with first-op timestamp, `LinkedHashMap` short-table-name counts, and total used as `event_count`. Single group → `DsTransaction` xid/csn/`getTransactionBeginTime()`; several → each group's own xid/csn and first-op `getTimestamp()` (null → `""`). Verify tests 1–8 pass with `mvn test`.
- [x] 2.2 Change `writeJson` to write one `JsonObject` for one marker or a `JsonArray` for several via `jsonWriter.write(JsonStructure)`, using `getJsonWriterFactory()` and the charset branch guarded by `this.config == null || this.config.getCharset() == null`. Verify `mvn test` passes with no NPE and the grouped tests parse a `JsonArray`.
- [x] 2.3 Update the class Javadoc to state the one-marker-per-source-transaction contract and the two message forms, and add a comment on `endTx` that the Kafka handler never calls it in tx mode (keep `endTx`/`metaDataChanged` as no-ops). Verify `TransactionMetaDataType` is unchanged (`git diff --stat` shows no change to it).

## 3. README

- [x] 3.1 Update `README.md`: document the contract (exactly one marker per source transaction `xid:csn`, grouped deliveries under `GROUPTRANSOPS`), the two message forms (single object; array in commit order with each marker's own xid/csn/tx_ts) with examples, change the top example's `tx_ts` from a number to a string, and add a rule that `MAXTRANSOPS` must stay unset in rep3 (it splits source transactions and produces partial markers). Note the message size bound (~180–280 B per marker, ~276 KB at GROUPTRANSOPS 1000). Verify by reviewing the rendered README and that every JSON example is valid (`python3 -m json.tool` on each).

## 4. Integration check

- [x] 4.1 Run `mvn clean package` and verify all 8 tests pass and `target/gg-custom-formatters-1.0-SNAPSHOT.jar` is produced with no `pom.xml` changes.
