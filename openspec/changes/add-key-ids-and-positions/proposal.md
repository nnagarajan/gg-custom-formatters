# Proposal

## Why

A marker today says only *how many* operations a source transaction has per table. A consumer cannot tell *which* rows changed or match a marker to the exact data messages it received, so it cannot deduplicate replays after a Replicat restart. Separately, `MAXTRANSOPS` must stay unset, so one large source transaction (bulk DML) becomes one unbounded delivery. We want each marker to list the key and trail position of every operation, and to support `MAXTRANSOPS` so message size has a fixed upper bound, with each marker saying whether its source transaction is complete.

## What Changes

- Each `data_collections` entry gains three fields:
  - `key_columns`: the table's key column names, in key order.
  - `key_ids`: one array of key values (strings) per operation, in trail order, in `key_columns` order, for example `[["101","A"],["102","B"]]`. Single-column keys are wrapped too (`[["101"]]`).
  - `pos`: one 20-digit trail position (`DsOperation.getPosition()`) per operation, index-aligned with `key_ids`, the same `pos` the standard GoldenGate JSON formatter emits on data messages.
- Each marker gains `tx_complete` (boolean): `true` when the marker contains its source transaction's last operation, `false` when the transaction continues in a later delivery.
- **`MAXTRANSOPS` becomes supported.** A source transaction split across deliveries produces one marker per delivery (fragment), all with the same `xid:csn`. **BREAKING** for consumers that assume one marker per `xid:csn` or that `event_count` is the whole transaction's count: `event_count` now means operations of that transaction *in this delivery*. Consumers merge fragments by `xid:csn` until `tx_complete` is `true` and deduplicate by `pos`.
- Key columns come from a new optional key column file (`SCHEMA.TABLE=COL1,COL2`, set via `gg.handler.<name>.format.keyColumnsFile`), falling back to the table's primary key from GoldenGate metadata.
- Fail fast: the formatter throws, and Replicat abends, when a key column is missing from a record, a key value is NULL, a `DO_TRUNCATE` operation arrives, a mapped column does not exist in the table, or a table has neither a primary key nor a mapping. A nullable mapped column logs a WARN.
- Keys are assumed immutable (enforced by the source database). Key values are read from the after image, falling back to the before image; for deletes from the before image.
- README: documents the new fields, `MAXTRANSOPS` support and fragment merging, the key column file, the abend rules with recovery steps, the supplemental logging requirement, and the size bound per `MAXTRANSOPS`.

Out of scope: detecting or handling key changes; a `tx_fragment` (BEGIN/MIDDLE/END) field; typed (non-string) key values; truncate support; splitting markers into separate Kafka records; changes to the Spark consumer (separate repo).

## Capabilities

### New Capabilities
_None._

### Modified Capabilities
- `transaction-boundary-markers`: markers gain `key_columns`, `key_ids`, `pos` and `tx_complete`; one marker per source transaction *per delivery* (fragments under `MAXTRANSOPS`); key resolution, key column file and abend rules are added; the shape-compatibility requirement is relaxed to allow the new fields.

Prerequisite: the main spec for `transaction-boundary-markers` is created when the completed change `one-marker-per-source-transaction` is archived. That change must be archived before this one.

## Impact

- Code: `TransactionBoundaryEventJsonFormatter.java` (key and position collection, key column file loading, completeness flag, validation and abends), `TransactionMetaDataType.java` (new field names). Likely a small new class for key column resolution.
- Tests: `TransactionBoundaryEventJsonFormatterTest.java` extended (JUnit 4, Mockito).
- Docs: `README.md`.
- Kafka topic `dev.transaction_metadata_json`: about 35–80 bytes more per operation (~38 KB per 1000 operations with numeric keys, ~79 KB with UUID + timestamp keys). Size per message is bounded by `MAXTRANSOPS`.
- Downstream consumer (`transactional-cdc-processing-spark`): must handle fragments (merge by `xid:csn` until `tx_complete`, dedupe by `pos`) before rep3 sets `MAXTRANSOPS`. New fields alone are additive.
- Operations: rep3 may set `MAXTRANSOPS`. Source supplemental logging must include every mapped key column for updates; Extract must not capture truncates.
- Unverified GoldenGate behavior (`getTxState()` / `isLast()` / `getNextTranID()` under `MAXTRANSOPS`, before-image key availability) is checked by a spike before implementation.
