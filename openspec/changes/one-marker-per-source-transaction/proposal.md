# Proposal

## Why

`TransactionBoundaryEventJsonFormatter` (replicat `rep3`, topic `dev.transaction_metadata_json`) builds its marker entirely from the delivered `DsTransaction`: its xid, csn, `getTotalOps()` and every operation it holds. That is only correct when one delivery holds exactly one source transaction. With `GROUPTRANSOPS` (default 1000) Replicat groups several whole source transactions into one `DsTransaction`, whose xid/csn are the *first* source transaction's (they are `private final`, set when the delivery begins). The result is one marker under T1's `xid:csn` with counts summed across T1 and T2: T1 never matches, T2 never gets a marker, and the downstream Spark pipeline, which emits a transaction only when per-table counts match exactly, blocks permanently. This has not happened yet only because no delivery so far has been grouped; it will happen after the next restart or backlog.

## What Changes

- The formatter emits exactly one marker per source transaction, identified by each operation's own `DsOperation.getXidStr()` / `getCsnStr()` (falling back to the `DsTransaction`'s value when an operation's id is null).
- Operations are grouped in trail order; two transactions with the same CSN but different xids are separate markers.
- Each marker's `event_count` is its own operation count; `data_collections` lists its own per-table counts in first-appearance order.
- Output form depends on how many source transactions the delivery holds:
  - **One:** a single JSON object with the same fields as today (xid, csn and `tx_ts` from the `DsTransaction`).
  - **Several:** a JSON array of marker objects in commit order, each with its own xid, csn and `tx_ts` (its first operation's timestamp). **BREAKING** for any consumer that only parses an object; the one known consumer (`CdcJsonParsing.parseTransactionMetadata`) already parses both forms.
- Output is still written once, on the delivery's last operation, through the existing `JsonWriterFactory` and charset handling; a null `config` no longer causes an NPE.
- Field names (`TransactionMetaDataType`) and the marker object's shape are unchanged.
- README documents the one-marker-per-source-transaction contract, both message forms, the `MAXTRANSOPS`-must-stay-unset rule, and corrects `tx_ts` to a string.

Out of scope: switching to `mode=op`; splitting markers into separate Kafka records (possible later via `customKafkaProducer`); supporting `MAXTRANSOPS`; implementing `endTx` / `metaDataChanged`; build or deployment changes.

## Capabilities

### New Capabilities
- `transaction-boundary-markers`: the transaction marker messages the formatter emits per Replicat delivery: marker identity, per-table counts, message forms, and when output is written.

### Modified Capabilities
_None. No specs exist yet._

## Impact

- Code: `src/main/java/com/technext/goldengate/dasource/formatter/TransactionBoundaryEventJsonFormatter.java` (rewrite of marker building and writing). `TransactionMetaDataType` unchanged.
- Tests: `src/test/java/.../TransactionBoundaryEventJsonFormatterTest.java` replaced by 8 tests (JUnit 4, Mockito 3.12).
- Docs: `README.md`.
- Kafka topic `dev.transaction_metadata_json`: grouped deliveries now produce a JSON array message (≈180–280 bytes per marker, ≈276 KB uncompressed at GROUPTRANSOPS 1000, under the ~1 MB default `max.message.bytes`; gzip enabled).
- Operations: replicat `rep3` must keep `MAXTRANSOPS` unset. Deployment (copy jar to `customlib`, restart rep3) is manual and order-independent with the Spark pipeline.
