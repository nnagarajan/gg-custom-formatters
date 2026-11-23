# Spec Delta

## MODIFIED Requirements

### Requirement: One marker per source transaction
The formatter SHALL emit exactly one marker for each distinct source transaction in a delivery, where a source transaction is identified by the pair of its operations' own xid and csn. Markers SHALL appear in trail (commit) order. Two transactions with the same csn but different xids SHALL be separate markers. When a source transaction is split across several deliveries, each delivery SHALL contain one marker for the part of that transaction it holds, so the transaction is described by one marker per delivery, all with the same xid and csn.

#### Scenario: Grouped delivery of three source transactions
- **WHEN** one delivery, whose own ids are X1:500, holds operations from X1:500 (ORDERS 1, ORDER_LINE_ITEMS 2), then X2:600 (ORDERS 1, ORDER_DETAILS 1), then X3:700 (ORDER_LINE_ITEMS 3)
- **THEN** the message contains three markers, in the order X1:500, X2:600, X3:700, each with only its own counts

#### Scenario: Same csn, different xid
- **WHEN** one delivery holds operations from A:500 and B:500
- **THEN** the message contains two markers, one for A:500 and one for B:500

#### Scenario: Many grouped transactions
- **WHEN** one delivery holds 50 source transactions of 1 to 4 operations each
- **THEN** the message contains 50 markers, and the sum of their `event_count` values equals the number of delivered operations

#### Scenario: Source transaction split across deliveries
- **WHEN** source transaction X1:500 with 3 ORDER_LINE_ITEMS operations is delivered as 2 operations in one delivery and 1 operation in the next
- **THEN** the first message has a marker X1:500 with `event_count` 2, the second message has a marker X1:500 with `event_count` 1, and together they list all 3 operations once

### Requirement: Per-marker counts
Each marker's `event_count` SHALL equal the number of operations of that source transaction in the delivery. Its `data_collections` SHALL list one entry per table (short table name) touched by that source transaction in the delivery, in first-appearance order, whose `event_count` is that table's operation count in the delivery. The per-table counts SHALL sum to the marker's `event_count`.

#### Scenario: Single transaction across three tables
- **WHEN** a delivery holds one source transaction with 1 ORDERS, 1 ORDER_DETAILS and 3 ORDER_LINE_ITEMS operations
- **THEN** the marker has `event_count` 5 and `data_collections` ORDERS = 1, ORDER_DETAILS = 1, ORDER_LINE_ITEMS = 3

#### Scenario: Two operations on one table
- **WHEN** a delivery holds one source transaction with 2 operations on SCHEMA1.TABLE1
- **THEN** the marker has `event_count` 2 and a single data collection TABLE1 = 2

#### Scenario: Fragment counts only its own operations
- **WHEN** a delivery holds the last 1 of 3 ORDERS operations of a split source transaction
- **THEN** that marker has `event_count` 1 and `data_collections` ORDERS = 1

### Requirement: Message form for a single source transaction
When a delivery holds exactly one source transaction, the message SHALL be a single JSON object with fields `xid`, `csn`, `tx_ts`, `event_count`, `tx_complete` and `data_collections`, where `xid`, `csn` and `tx_ts` (a string) are the delivery's own transaction id, commit sequence number and begin time.

#### Scenario: Single source transaction is one JSON object
- **WHEN** a delivery with ids TEST-XID-001:123456 and begin time "2023-12-01 10:00:00.000" holds one source transaction
- **THEN** the message is a JSON object with `xid` "TEST-XID-001", `csn` "123456" and `tx_ts` "2023-12-01 10:00:00.000"

### Requirement: Marker shape compatibility
Marker objects SHALL keep the existing field names `xid`, `csn`, `tx_ts`, `event_count`, `data_collections`, `data_collection`. Each marker object SHALL have exactly the fields `xid`, `csn`, `tx_ts`, `event_count`, `tx_complete` and `data_collections`, and each data collection entry SHALL have exactly `data_collection`, `event_count`, `key_columns`, `key_ids` and `pos`.

#### Scenario: Grouped marker fields
- **WHEN** a grouped delivery produces an array of markers
- **THEN** every marker object has exactly the fields `xid`, `csn`, `tx_ts`, `event_count`, `tx_complete` and `data_collections`, and every data collection entry has exactly `data_collection`, `event_count`, `key_columns`, `key_ids` and `pos`

## ADDED Requirements

### Requirement: Key columns per data collection
Each data collection entry SHALL have `key_columns`: a JSON array of the table's key column names, in key order. The key columns SHALL be the table's entry in the key column file when it has one, otherwise the table's primary key columns from GoldenGate metadata.

#### Scenario: Primary key from metadata
- **WHEN** table APPUSER.ORDERS has primary key ORDER_ID and no key column file entry
- **THEN** its data collection has `key_columns` ["ORDER_ID"]

#### Scenario: Key column file overrides the primary key
- **WHEN** the key column file maps APPUSER.ORDER_LINE_ITEMS to ORDER_ID,LINE_NO and the table's primary key is LINE_ITEM_ID
- **THEN** its data collection has `key_columns` ["ORDER_ID","LINE_NO"]

### Requirement: Key values per operation
Each data collection entry SHALL have `key_ids`: one JSON array per operation on that table in the marker, in trail order. Each inner array SHALL hold the operation's key values as strings, in `key_columns` order, including when there is only one key column. Inserts and updates SHALL use the after value, falling back to the before value; deletes SHALL use the before value.

#### Scenario: Composite key
- **WHEN** a marker holds inserts on ORDER_LINE_ITEMS for keys (101, A) then (102, B), with `key_columns` ["ORDER_ID","LINE_NO"]
- **THEN** `key_ids` is [["101","A"],["102","B"]]

#### Scenario: Single-column key is wrapped
- **WHEN** a marker holds inserts on ORDERS for ORDER_ID 101 and 102
- **THEN** `key_ids` is [["101"],["102"]]

#### Scenario: Update without the key in the after image
- **WHEN** an update on ORDERS carries ORDER_ID 101 only in its before image
- **THEN** its `key_ids` entry is ["101"]

#### Scenario: Delete uses the before image
- **WHEN** a delete on ORDERS removes the row with ORDER_ID 101
- **THEN** its `key_ids` entry is ["101"]

#### Scenario: Same row changed twice
- **WHEN** a marker holds two updates on ORDERS ORDER_ID 101
- **THEN** `key_ids` contains ["101"] twice, once per operation

### Requirement: Trail position per operation
Each data collection entry SHALL have `pos`: one string per operation on that table in the marker, holding the operation's 20-digit trail position (10-digit trail sequence number followed by 10-digit byte offset), in the same order as `key_ids`. `key_ids` and `pos` SHALL each have as many entries as the entry's `event_count`.

#### Scenario: Positions align with keys
- **WHEN** a marker holds ORDERS operations for ORDER_ID 101 at trail sequence 1, offset 4321, then ORDER_ID 102 at offset 4587
- **THEN** `key_ids` is [["101"],["102"]] and `pos` is ["00000000010000004321","00000000010000004587"]

#### Scenario: Array lengths equal the count
- **WHEN** a data collection has `event_count` 50
- **THEN** its `key_ids` and `pos` arrays each have 50 entries

### Requirement: Transaction completeness flag
Each marker SHALL have `tx_complete`: `true` when the marker contains its source transaction's last operation, `false` when the transaction continues in a later delivery.

#### Scenario: Whole transaction in one delivery
- **WHEN** a delivery holds all operations of source transaction X2:600
- **THEN** the X2:600 marker has `tx_complete` true

#### Scenario: Split transaction
- **WHEN** source transaction X1:500 is delivered as operations 1-2 in one delivery and operation 3 in the next
- **THEN** the first X1:500 marker has `tx_complete` false and the second has `tx_complete` true

#### Scenario: Split transaction grouped with others
- **WHEN** one delivery holds the end of X1:500, all of X2:600 and the start of X3:700
- **THEN** the markers are X1:500 `tx_complete` true, X2:600 `tx_complete` true, X3:700 `tx_complete` false

### Requirement: Key column file
The formatter SHALL accept an optional key column file, named by a formatter property, with one line per table of the form `SCHEMA.TABLE=COL1,COL2,...`. Table names SHALL be matched case-insensitively against the fully qualified table name of each operation. Without the property, all tables use their primary key from metadata.

#### Scenario: File maps a table
- **WHEN** the file contains `APPUSER.ORDER_LINE_ITEMS=ORDER_ID,LINE_NO` and an operation on appuser.order_line_items is formatted
- **THEN** that table's `key_columns` is ["ORDER_ID","LINE_NO"]

#### Scenario: No file configured
- **WHEN** no key column file property is set
- **THEN** every table's `key_columns` is its primary key from metadata

#### Scenario: Unreadable file
- **WHEN** the key column file property names a file that does not exist or has a malformed line
- **THEN** the formatter fails at startup and Replicat abends with an error naming the file and line

### Requirement: Abend on missing key value
When any key column of an operation is absent from both its before and after images (or from the before image of a delete), the formatter SHALL fail and Replicat SHALL abend without writing the message. The error SHALL name the table, column, operation type and trail position, and SHALL say the value is missing.

#### Scenario: Compressed update without a mapped column
- **WHEN** an update on ORDER_LINE_ITEMS at position 00000000010000004587 carries no LINE_NO value in either image
- **THEN** Replicat abends with an error naming ORDER_LINE_ITEMS, LINE_NO, the update operation, position 00000000010000004587 and "missing"

### Requirement: Abend on NULL key value
When any key column of an operation has a NULL value, the formatter SHALL fail and Replicat SHALL abend without writing the message. The error SHALL name the table, column, operation type and trail position, and SHALL say the value is NULL.

#### Scenario: NULL in a mapped business key
- **WHEN** an insert on ORDER_LINE_ITEMS has LINE_NO NULL
- **THEN** Replicat abends with an error naming ORDER_LINE_ITEMS, LINE_NO, the insert operation, its position and "NULL"

### Requirement: Abend on truncate
When a delivery contains a truncate operation, the formatter SHALL fail and Replicat SHALL abend, with an error naming the table and position and saying truncates must not be captured.

#### Scenario: Truncate reaches the formatter
- **WHEN** a delivery contains a truncate on APPUSER.ORDERS
- **THEN** Replicat abends with an error naming APPUSER.ORDERS and stating truncates must not be captured

### Requirement: Abend on unresolvable key definition
When the formatter first sees a table, it SHALL fail and Replicat SHALL abend if a column in the table's key column file entry does not exist in the table, or if the table has no primary key and no key column file entry.

#### Scenario: Mapped column does not exist
- **WHEN** the key column file maps APPUSER.ORDER_LINE_ITEMS to ORDER_ID,LINE_NUM and the table has no column LINE_NUM
- **THEN** Replicat abends on the first operation for that table with an error naming the table and LINE_NUM

#### Scenario: Table without primary key or mapping
- **WHEN** an operation arrives for a table with no primary key and no key column file entry
- **THEN** Replicat abends with an error naming the table and saying it needs a key column mapping

### Requirement: Warning for nullable mapped columns
When the formatter first sees a table whose key column file entry includes a nullable column, it SHALL log a warning naming the table and column, and SHALL continue.

#### Scenario: Nullable business key column
- **WHEN** the key column file maps APPUSER.ORDER_LINE_ITEMS to ORDER_ID,LINE_NO and LINE_NO is nullable
- **THEN** a WARN is logged naming APPUSER.ORDER_LINE_ITEMS and LINE_NO, and markers are still written
