# transaction-boundary-markers Specification

## Purpose
Defines the transaction marker messages the GoldenGate formatter publishes for each Replicat delivery, so a downstream consumer can know the exact per-table operation count of every source transaction (`xid:csn`).

## Requirements

### Requirement: One marker per source transaction
The formatter SHALL emit exactly one marker for each distinct source transaction in a delivery, where a source transaction is identified by the pair of its operations' own xid and csn. Markers SHALL appear in trail (commit) order. Two transactions with the same csn but different xids SHALL be separate markers.

#### Scenario: Grouped delivery of three source transactions
- **WHEN** one delivery, whose own ids are X1:500, holds operations from X1:500 (ORDERS 1, ORDER_LINE_ITEMS 2), then X2:600 (ORDERS 1, ORDER_DETAILS 1), then X3:700 (ORDER_LINE_ITEMS 3)
- **THEN** the message contains three markers, in the order X1:500, X2:600, X3:700, each with only its own counts

#### Scenario: Same csn, different xid
- **WHEN** one delivery holds operations from A:500 and B:500
- **THEN** the message contains two markers, one for A:500 and one for B:500

#### Scenario: Many grouped transactions
- **WHEN** one delivery holds 50 source transactions of 1 to 4 operations each
- **THEN** the message contains 50 markers, and the sum of their `event_count` values equals the number of delivered operations

### Requirement: Per-marker counts
Each marker's `event_count` SHALL equal the number of operations of that source transaction in the delivery. Its `data_collections` SHALL list one entry per table (short table name) touched by that source transaction, in first-appearance order, whose `event_count` is that table's operation count. The per-table counts SHALL sum to the marker's `event_count`.

#### Scenario: Single transaction across three tables
- **WHEN** a delivery holds one source transaction with 1 ORDERS, 1 ORDER_DETAILS and 3 ORDER_LINE_ITEMS operations
- **THEN** the marker has `event_count` 5 and `data_collections` ORDERS = 1, ORDER_DETAILS = 1, ORDER_LINE_ITEMS = 3

#### Scenario: Two operations on one table
- **WHEN** a delivery holds one source transaction with 2 operations on SCHEMA1.TABLE1
- **THEN** the marker has `event_count` 2 and a single data collection TABLE1 = 2

### Requirement: Message form for a single source transaction
When a delivery holds exactly one source transaction, the message SHALL be a single JSON object with fields `xid`, `csn`, `tx_ts`, `event_count` and `data_collections`, where `xid`, `csn` and `tx_ts` (a string) are the delivery's own transaction id, commit sequence number and begin time.

#### Scenario: Single source transaction is one JSON object
- **WHEN** a delivery with ids TEST-XID-001:123456 and begin time "2023-12-01 10:00:00.000" holds one source transaction
- **THEN** the message is a JSON object with `xid` "TEST-XID-001", `csn` "123456" and `tx_ts` "2023-12-01 10:00:00.000"

### Requirement: Message form for several source transactions
When a delivery holds more than one source transaction, the message SHALL be a JSON array of marker objects in commit order. Each object SHALL have the same fields as the single-transaction form, with its own source transaction's `xid` and `csn`, and `tx_ts` set to the timestamp of that transaction's first operation in the delivery.

#### Scenario: Each grouped marker carries its own timestamp
- **WHEN** a delivery holds X1 whose first operation has timestamp t1 and X2 whose first operation has timestamp t2
- **THEN** the message is a JSON array whose first marker has `tx_ts` t1 and second marker has `tx_ts` t2

### Requirement: Fallback to delivery ids
When an operation does not carry its own xid or csn, the formatter SHALL use the delivery's corresponding value for that operation.

#### Scenario: Operations without their own ids
- **WHEN** a delivery with ids D1:900 holds operations whose own xid and csn are absent
- **THEN** those operations are counted in a single marker with `xid` D1 and `csn` 900

### Requirement: Output written once per delivery
The formatter SHALL write the delivery's message only when formatting the delivery's last operation, and SHALL write nothing for any other operation.

#### Scenario: Non-last operation writes nothing
- **WHEN** an operation that is not the delivery's last operation is formatted
- **THEN** zero bytes are written to the output

#### Scenario: Last operation writes the message
- **WHEN** every operation of a delivery is formatted in order into the same output
- **THEN** the output contains exactly one JSON value (object or array) describing the whole delivery

### Requirement: Marker shape compatibility
Marker objects SHALL keep the existing field names `xid`, `csn`, `tx_ts`, `event_count`, `data_collections`, `data_collection`, and no fields SHALL be added or removed.

#### Scenario: Grouped marker fields
- **WHEN** a grouped delivery produces an array of markers
- **THEN** every marker object has exactly the fields `xid`, `csn`, `tx_ts`, `event_count` and `data_collections`, and every data collection entry has exactly `data_collection` and `event_count`
