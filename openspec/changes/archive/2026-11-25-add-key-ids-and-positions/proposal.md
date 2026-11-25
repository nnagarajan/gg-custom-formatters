# Proposal

## Why

A marker today says only *how many* operations a source transaction has per table. A consumer cannot tell *which* rows changed or match a marker to the exact data messages it received, so it cannot deduplicate replays after a Replicat restart. Separately, `MAXTRANSOPS` must stay unset, so one large source transaction (bulk DML) becomes one unbounded delivery. We want each marker to list the key and trail position of every operation, and to support `MAXTRANSOPS` so message size has a fixed upper bound, with each marker saying whether its source transaction is complete. Not every team needs both keys and positions, so each can be turned off. Analysis after implementation (sizes, replay behavior, consumer duplicate handling) showed that most deployments are better served by neither: counts-only markers without `MAXTRANSOPS` stay about 300 bytes whatever the transaction size, so they need no size bound, no fragment merging and no key configuration. That is now the documented recommended setup; keys, positions and `MAXTRANSOPS` remain supported for consumers that need them.

## What Changes

- Each `data_collections` entry gains three fields:
  - `key_columns`: the table's key column names, in key order.
  - `key_ids`: one array of key values (strings) per operation, in trail order, in `key_columns` order, for example `[["101","A"],["102","B"]]`. Single-column keys are wrapped too (`[["101"]]`).
  - `pos`: one 20-digit trail position (`DsOperation.getPosition()`) per operation, index-aligned with `key_ids`, the same `pos` the standard GoldenGate JSON formatter emits on data messages.
- Each marker gains `tx_complete` (boolean): `true` when the marker contains its source transaction's last operation, `false` when the transaction continues in a later delivery.
- **`MAXTRANSOPS` becomes supported.** A source transaction split across deliveries produces one marker per delivery (fragment), all with the same `xid:csn`. **BREAKING** for consumers that assume one marker per `xid:csn` or that `event_count` is the whole transaction's count: `event_count` now means operations of that transaction *in this delivery*. Consumers merge fragments by `xid:csn` until `tx_complete` is `true` and deduplicate by `pos`.
- Two optional properties, `gg.handler.<name>.format.markerKeys` and `gg.handler.<name>.format.markerPos` (both default `true`), let each team turn off the key fields, the positions, or both. Turned-off fields are omitted. With keys off, no key resolution or key abends happen and the key column file is not read.
- Key columns come from a new optional key column file (`SCHEMA.TABLE=COL1,COL2`, set via `gg.handler.<name>.format.keyColumnsFile`), falling back to the table's primary key from GoldenGate metadata. The file is read at startup (formatter `init`), only when `markerKeys` is true.
- Fail fast: the formatter throws, and Replicat abends, when (with `markerKeys` true) a key column is missing from a record, a key value is NULL, a `DO_TRUNCATE` operation arrives, a mapped column does not exist in the table, or a table has neither a primary key nor a mapping. A truncate abends whatever the flags. An invalid flag value or an unreadable key column file fails at startup. A nullable mapped column logs a WARN.
- Primary keys are assumed immutable (enforced by the source database). A mapped column that changes on update (for example `VERSION`) is reported with its after-image value. Key values are read from the after image, falling back to the before image; for deletes from the before image.
- README: recommends a default deployment, counts only (`markerKeys=false`, `markerPos=false`) with `MAXTRANSOPS` unset, with its settings, measured size, consumer rules and limits; marks which caveats apply to it; adds caveats for rep3 and the data Replicat delivering different operations (for example `IGNOREDELETES` on one of them) and for rep3 being down (no markers, so no stuck-transaction alert); removes `IGNOREDELETES` from the example Replicat parameters. It also documents the caveats teams must act on (Caveats at a glance, Kafka Producer Settings, Consumer Guide, Capacity Planning), the new fields, the marker field flags, `MAXTRANSOPS` support and fragment merging, the key column file, the abend rules with recovery steps, the supplemental logging requirement, and the size bound per `MAXTRANSOPS`.

Out of scope: detecting or handling key changes; a `tx_fragment` (BEGIN/MIDDLE/END) field; typed (non-string) key values; truncate support; splitting markers into separate Kafka records; changes to the Spark consumer (separate repo).

## Capabilities

### New Capabilities
_None._

### Modified Capabilities
- `transaction-boundary-markers`: markers gain `key_columns`, `key_ids`, `pos` and `tx_complete`; one marker per source transaction *per delivery* (fragments under `MAXTRANSOPS`); key resolution, key column file and abend rules are added; the shape-compatibility requirement is relaxed to allow the new fields; the per-operation fields become selectable (`markerKeys`, `markerPos`).

Prerequisite: the main spec for `transaction-boundary-markers` is created when the completed change `one-marker-per-source-transaction` is archived. That change must be archived before this one.

## Impact

- Code: `TransactionBoundaryEventJsonFormatter.java` (key and position collection, marker field flags, key column file loading in `init`, completeness flag, validation and abends), `TransactionMetaDataType.java` (new field names), new `KeyColumnResolver.java` (key column file parsing, key resolution and values). Sample `src/main/resources/key_columns.properties`.
- Tests: `TransactionBoundaryEventJsonFormatterTest.java` extended (JUnit 4, Mockito).
- Docs: `README.md`.
- Kafka topic `dev.transaction_metadata_json`: measured about 42 bytes per operation with keys and positions for large transactions, about 131 bytes per operation for one-order transactions (marker header repeated); about 23 bytes per operation with `markerKeys=false`. 3.5M operations/day is about 150–460 MB/day uncompressed, 20–180 MB compressed. Size per message is bounded by `MAXTRANSOPS` in normal running but **not** on a replay after an abend, so producer, topic and consumer size limits must cover the largest source transaction (bulk trade affirm/confirm: ~6–12 MB for 50,000 trades).
- Kafka delivery: markers must go to one partition (fixed `keyMappingTemplate` or a 1-partition topic) to keep trail order; producer with idempotence, `acks=all`, at most 5 in-flight requests, and `max.request.size` above the largest uncompressed marker. README "Kafka Producer Settings".
- Downstream consumer (`transactional-cdc-processing-spark`): must handle fragments (merge by `xid:csn` until `tx_complete`), deduplicate by `pos` (a high-water mark works on one partition), and expect a whole-transaction replay that overlaps earlier fragments, before rep3 sets `MAXTRANSOPS`. New fields alone are additive. README "Consumer Guide".
- Recommended operation: `markerKeys=false`, `markerPos=false`, no `MAXTRANSOPS` (README "Recommended setup"). Markers are about 300 bytes (measured 296 bytes for 5 operations, 312 for 54,000), so a message holds at most `GROUPTRANSOPS` markers (about 300 KB at 1000) and default Kafka limits suffice. The current Spark consumer already handles this shape. rep3 and the data Replicat must deliver the same operations for the tracked tables; rep3 being down must be monitored separately.
- Operations with keys, positions or fragments: rep3 may set `MAXTRANSOPS` (keep `markerPos` true when it does, so fragments can be deduplicated). With `markerKeys` true, source supplemental logging must include every mapped key column for updates. Extract must not capture truncates.
- Unverified GoldenGate behavior (`getTxState()` / `isLast()` / `getNextTranID()` under `MAXTRANSOPS`, before-image key availability) was to be checked by a spike before implementation; the spike was skipped. Most of it was confirmed on rep3 on 2026-11-23 (design.md "Observed on rep3"). One finding affects consumers and operations: after an abend inside a split transaction, Replicat replays it as one whole delivery, so `MAXTRANSOPS` does not bound that marker and it overlaps fragments already published.
