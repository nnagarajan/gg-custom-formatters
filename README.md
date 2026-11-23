# gg-custom-formatters
## Overview
This repository contains custom formatters for Oracle GoldenGate that are inspired by the Debezium transaction metadata format. These formatters are designed to provide a structured representation of transactions, including commit sequence numbers (CSN), transaction IDs (XID), timestamps, and event counts.

## Contract: one marker per source transaction per delivery

With `gg.handler.kafkahandler.mode=tx` the Kafka handler sends **one Kafka message per Replicat delivery**. The formatter groups the delivery's operations by each operation's own source transaction (`xid:csn`), in trail order, and writes one marker per group. Downstream consumers (the Spark pipeline in `transactional-cdc-processing-spark`) rely on the counts, keys and positions being exact.

- `GROUPTRANSOPS` (default 1000) puts several whole source transactions in one delivery.
- `MAXTRANSOPS` splits a large source transaction across several deliveries. Each delivery then holds a **fragment**: its marker has the same `xid:csn` as the other fragments, and `event_count` counts only that delivery's operations.

| Delivery holds | Kafka message |
|---|---|
| One source transaction (or fragment) | A single JSON object |
| Several | A JSON array of marker objects, in commit order |

- `tx_complete` is `true` when the marker contains its source transaction's last operation, and `false` when the transaction continues in a later delivery. Only the last marker of a message can be `false`.
- Two transactions with the same `csn` but different `xid` are separate markers.
- In the single-object form, `xid`, `csn` and `tx_ts` come from the delivered transaction (`tx_ts` is its begin time).
- In the array form, each marker has its own `xid` and `csn`, and `tx_ts` is the timestamp of that transaction's first operation in the delivery.
- `data_collections` lists tables (short names) in the order they first appear.
- `tx_ts` is a string.

**Consumers** must merge markers with the same `xid:csn` until one has `tx_complete: true`, and deduplicate operations by `pos` (after a Replicat restart an already published delivery can be sent again). Without `MAXTRANSOPS` every marker has `tx_complete: true`.

## Message Format

One source transaction (1 order with 1 detail row and 2 line items):

```json
{
  "xid": "1342848513.8.14.5716",
  "csn": "344374437",
  "tx_ts": "2026-11-07 21:24:36.000000",
  "event_count": 4,
  "tx_complete": true,
  "data_collections": [
    {
      "data_collection": "ORDERS",
      "event_count": 1,
      "key_columns": ["ORDER_ID"],
      "key_ids": [["101"]],
      "pos": ["00000000010000004321"]
    },
    {
      "data_collection": "ORDER_DETAILS",
      "event_count": 1,
      "key_columns": ["DETAIL_ID"],
      "key_ids": [["9"]],
      "pos": ["00000000010000004587"]
    },
    {
      "data_collection": "ORDER_LINE_ITEMS",
      "event_count": 2,
      "key_columns": ["ORDER_ID", "LINE_NO"],
      "key_ids": [["101", "A"], ["101", "B"]],
      "pos": ["00000000010000004853", "00000000010000005119"]
    }
  ]
}
```

A grouped delivery of T1 (2 operations) and T2 (1 operation):

```json
[
  {
    "xid": "X1",
    "csn": "500",
    "tx_ts": "2026-11-07 21:24:36.000000",
    "event_count": 2,
    "tx_complete": true,
    "data_collections": [
      {
        "data_collection": "ORDERS",
        "event_count": 2,
        "key_columns": ["ORDER_ID"],
        "key_ids": [["101"], ["102"]],
        "pos": ["00000000010000004321", "00000000010000004587"]
      }
    ]
  },
  {
    "xid": "X2",
    "csn": "600",
    "tx_ts": "2026-11-07 21:24:37.000000",
    "event_count": 1,
    "tx_complete": true,
    "data_collections": [
      {
        "data_collection": "ORDER_LINE_ITEMS",
        "event_count": 1,
        "key_columns": ["ORDER_ID", "LINE_NO"],
        "key_ids": [["103", "A"]],
        "pos": ["00000000010000004853"]
      }
    ]
  }
]
```

A source transaction X1 of 3 operations split by `MAXTRANSOPS 2` (two messages):

```json
{
  "xid": "X1",
  "csn": "500",
  "tx_ts": "2026-11-07 21:24:36.000000",
  "event_count": 2,
  "tx_complete": false,
  "data_collections": [
    {
      "data_collection": "ORDER_LINE_ITEMS",
      "event_count": 2,
      "key_columns": ["ORDER_ID", "LINE_NO"],
      "key_ids": [["101", "A"], ["101", "B"]],
      "pos": ["00000000010000004321", "00000000010000004587"]
    }
  ]
}
```

```json
{
  "xid": "X1",
  "csn": "500",
  "tx_ts": "2026-11-07 21:24:36.000000",
  "event_count": 1,
  "tx_complete": true,
  "data_collections": [
    {
      "data_collection": "ORDER_LINE_ITEMS",
      "event_count": 1,
      "key_columns": ["ORDER_ID", "LINE_NO"],
      "key_ids": [["101", "C"]],
      "pos": ["00000000010000004853"]
    }
  ]
}
```

### Keys and positions

Each `data_collections` entry lists every operation on that table, in trail order:

| Field | Content |
|---|---|
| `key_columns` | The table's key column names, in key order: the primary key from GoldenGate metadata, or the table's entry in the key column file. |
| `key_ids` | One array of key values per operation, in `key_columns` order. Values are strings. A single-column key is still wrapped: `[["101"]]`. |
| `pos` | One trail position per operation: 10-digit trail sequence number followed by 10-digit byte offset (`00000000010000004321` = sequence 1, offset 4321). This is the same `pos` the standard GoldenGate JSON formatter writes on data messages. |

- `key_ids[i]` and `pos[i]` describe the same operation, and both arrays have `event_count` entries.
- A row changed twice in one transaction appears twice in `key_ids` (with two different `pos` values).
- Inserts and updates take key values from the after image, falling back to the before image (compressed updates often carry the key only there). Deletes take them from the before image. Keys are assumed never to change.
- Consumers can match a marker to its data messages by `pos`, and should deduplicate by `pos`: after a Replicat restart, an already published delivery can be sent again.

Message size: a marker is about 200 bytes plus about 35–80 bytes per operation (about 23 for `pos`, the rest for key values: about 12 for a short numeric key, about 60 for a UUID plus timestamp). 1,000 operations add about 38 KB with numeric keys or about 79 KB with wide keys. The broker's default `max.message.bytes` is about 1 MB. `MAXTRANSOPS` bounds the operations per delivery, so it bounds the message: at `MAXTRANSOPS 1000` a message stays under about 80 KB of keys and positions, plus about 200 bytes per grouped transaction (about 400 KB in the worst case at `GROUPTRANSOPS 1000`). Without `MAXTRANSOPS`, one source transaction of roughly 25,000 operations with numeric keys (13,000 with wide keys) reaches 1 MB.

## Configuration

### 1. Kafka Handler Integration
To use the custom formatter with the GoldenGate Kafka handler, see the following property:
```properties
gg.handler.kafkahandler.format=
```
### 2. Custom Library Directory
Create a directory for custom libraries and copy the required artifacts:

```properties
/opt/oracle/ggbd/ggjava/resources/customlib
```
Update the classpath in your configuration as follows:

````properties
gg.classpath=gg.classpath=dirprm/:/var/lib/kafka/libs/*:/opt/oracle/ggmabd/home/opt/DependencyDownloader/dependencies/kafka_3.3.2/*:/opt/oracle/ggbd/ggjava/resources/customlib/*
````

### 3. Replicat Settings
Below is an example of a replicat parameter files (`replicat.prm`):

```shell
REPLICAT rep3
TARGETDB LIBFILE libggjava.so SET property=/opt/oracle/ggbd/dirprm/rep3.props
REPORTCOUNT EVERY 15 SECONDS, RATE
GROUPTRANSOPS 1000
--MAXTRANSOPS 1000
IGNOREDELETES
MAP ORCLPDB.APPUSER.ORDERS, TARGET APPUSER.ORDERS;
MAP ORCLPDB.APPUSER.ORDER_DETAILS, TARGET APPUSER.ORDER_DETAILS;
MAP ORCLPDB.APPUSER.ORDER_LINE_ITEMS, TARGET APPUSER.ORDER_LINE_ITEMS;
```

> **`MAXTRANSOPS` is supported.** It splits a large source transaction across deliveries; each fragment gets its own marker with `tx_complete: false` until the last one. It keeps every message within a fixed size (see Message Format).
>
> **Rollout order:** deploy this formatter with `MAXTRANSOPS` still commented out (the new fields are additive), update the consumer to merge fragments by `xid:csn` and deduplicate by `pos`, then enable `MAXTRANSOPS` and restart rep3.
>
> **Verify on first use:** `tx_complete` relies on Replicat marking each operation's position in its source transaction (`DsOperation.isLast()`). Before relying on it, run one split transaction and check that its markers say `false` ... `true` (not all `false`).

Example Kafka handler properties ('.props''):

```properties
gg.handlerlist=kafkahandler
gg.handler.kafkahandler.type=kafka
gg.handler.kafkahandler.kafkaProducerConfigFile=kp.properties
#The following resolves the topic name using the short table name
gg.handler.kafkahandler.topicMappingTemplate=dev.transaction_metadata_json
gg.handler.kafkahandler.mode=tx
gg.handler.kafkahandler.transactionsEnabled=false
gg.handler.kafkahandler.format=com.technext.goldengate.dasource.formatter.TransactionBoundaryEventJsonFormatter
#Sample gg.classpath for Apache Kafka
gg.classpath=dirprm/:/var/lib/kafka/libs/*:/opt/oracle/ggmabd/home/opt/DependencyDownloader/dependencies/kafka_3.3.2/*:/opt/oracle/ggbd/ggjava/resources/customlib/*
#Sample gg.classpath for HDP
javawriter.stats.full=TRUE
javawriter.stats.display=TRUE

###KAFKA Properties file ###
gg.log=log4j
gg.log.level=debug
gg.report.time=30sec
jvm.bootoptions=-Xms4g -Xmx6g

```
### 4. Key Columns

By default each table's `key_columns` is its primary key from GoldenGate metadata. To use other columns (for example a business key, or a table without a primary key), point the formatter to a key column file:

```properties
gg.handler.kafkahandler.format.keyColumnsFile=dirprm/key_columns.properties
```

```properties
# dirprm/key_columns.properties
# SCHEMA.TABLE=COL1,COL2,...   columns in key_ids order
APPUSER.ORDERS=ORDER_ID
APPUSER.ORDER_DETAILS=ORDER_ID,DETAIL_ID
APPUSER.ORDER_LINE_ITEMS=ORDER_ID,LINE_NO
```

- A commented sample is in [src/main/resources/key_columns.properties](src/main/resources/key_columns.properties). Copy it to the GoldenGate `dirprm` directory (for example `/opt/oracle/ggbd/dirprm/key_columns.properties`); the formatter reads it from that path, not from the jar.
- One line per table. Blank lines and lines starting with `#` or `!` are ignored.
- Table and column names are matched case-insensitively. The table name is the delivered (target) `SCHEMA.TABLE`, as in the `MAP ... TARGET` clause.
- Tables not in the file use their primary key. A table with neither a primary key nor an entry abends Replicat.
- The file is read once at startup. A missing file or a malformed line (no `=`, empty or repeated column name, table listed twice) stops Replicat with an error naming the file and line. Restart rep3 after editing it.
- Every mapped column must be captured for updates as well as inserts, which needs supplemental logging on the source for those columns and their before images in the trail:

  ```
  ADD TRANDATA APPUSER.ORDER_LINE_ITEMS COLS (ORDER_ID, LINE_NO)
  ```

  plus `LOGALLSUPCOLS` in the Extract parameter file.

### 5. Abend Rules and Recovery

The formatter never writes a marker it cannot fill in exactly. In these cases it throws, nothing is written for that delivery, and Replicat abends:

| Cause | Error (example) |
|---|---|
| A key column is not in the record (neither image) | `Key column LINE_NO is MISSING for APPUSER.ORDER_LINE_ITEMS op=DO_UPDATE_FIELDCOMP pos=00000000010000004587 xid=X1 csn=500` |
| A key column's value is NULL | `Key column LINE_NO is NULL for APPUSER.ORDER_LINE_ITEMS op=DO_INSERT pos=... xid=... csn=...` |
| A truncate reaches the formatter | `Unsupported operation DO_TRUNCATE on APPUSER.ORDERS pos=...: truncates must not be captured (GETTRUNCATES)` |
| A mapped column does not exist in the table | `Key column file maps APPUSER.ORDER_LINE_ITEMS to [ORDER_ID, LINE_NUM], but APPUSER.ORDER_LINE_ITEMS has no column LINE_NUM` |
| A table has no primary key and no mapping | `Table APPUSER.X has no primary key and no key column mapping; add it to the key column file (...)` |
| The key column file is missing or malformed (at startup) | `Malformed key column file dirprm/key_columns.properties line 2: ...` |

A mapped column that is nullable only logs a warning on first sight of the table (`Key column APPUSER.ORDER_LINE_ITEMS.LINE_NO is nullable; a NULL value in it will abend Replicat`).

Extract must not capture truncates for these tables (no `GETTRUNCATES`).

**Recovery.** A missing or NULL key is already in the trail, so restarting rep3 abends again at the same position. Fixing supplemental logging only helps records captured afterwards. To get past it, either:

1. change or remove the table's entry in the key column file so the record has a key, then restart rep3;
2. skip the source transaction (`START REPLICAT rep3 SKIPTRANSACTION`); its marker is lost, so tell the consumer; or
3. fix supplemental logging, re-capture that range, and reposition rep3.

## Testing

> The sample outputs below were recorded before `key_columns`, `key_ids` and `pos` existed and omit them for brevity. Each `data_collections` entry now also carries those three fields, with one `key_ids` and one `pos` entry per operation (for example 50 each for `ORDERS` in Example 1).

### Example 1

The following PL/SQL block was executed to generate transaction event:

```oracle
SET TIMING ON;
BEGIN
	FOR I IN  1..50
	LOOP
		UPDATE ORDERS SET ORDER_STATUS='CONFIRMED' WHERE ORDER_ID=1;
		UPDATE ORDER_DETAILS SET DELIVERY_STATUS='IN_TRANSIT' WHERE ORDER_ID=1;
		UPDATE ORDER_LINE_ITEMS SET ITEM_QTY=100 WHERE ORDER_ID=1 AND LINE_ITEM_ID=1;
	END LOOP;
END;
/
COMMIT;
```

Sample output:

```json
{
  "xid": "1342848513.10.11.105432",
  "csn": "334506722",
  "tx_ts": "2025-07-07 22:34:16.000000",
  "event_count": 150,
  "tx_complete": true,
  "data_collections": [
    {
      "data_collection": "ORDERS",
      "event_count": 50
    },
    {
      "data_collection": "ORDER_DETAILS",
      "event_count": 50
    },
    {
      "data_collection": "ORDER_LINE_ITEMS",
      "event_count": 50
    }
  ]
}
```

### Example 2

```oracle
SET TIMING ON;
BEGIN
	FOR I IN  1..50
	LOOP
		UPDATE ORDERS SET ORDER_STATUS='CONFIRMED' WHERE ORDER_ID=1;
		UPDATE ORDER_DETAILS SET DELIVERY_STATUS='IN_TRANSIT' WHERE ORDER_ID=1;
	END LOOP;
	UPDATE ORDER_LINE_ITEMS SET ITEM_QTY=200 WHERE ORDER_ID=1 AND LINE_ITEM_ID=1;
END;
/
COMMIT;
```

Sample output:

```json
{
  "xid": "1342848513.10.11.105432",
  "csn": "334506722",
  "tx_ts": "2025-07-07 22:34:16.000000",
  "event_count": 101,
  "tx_complete": true,
  "data_collections": [
    {
      "data_collection": "ORDERS",
      "event_count": 50
    },
    {
      "data_collection": "ORDER_DETAILS",
      "event_count": 50
    },
    {
      "data_collection": "ORDER_LINE_ITEMS",
      "event_count": 1
    }
  ]
}
```

### Example 3

Pressure test with 150,000 updates to the same transaction

```oracle
SET TIMING ON;
BEGIN
    FOR I IN  1..50000
        LOOP
            UPDATE ORDERS SET ORDER_STATUS='CONFIRMED' WHERE ORDER_ID=1;
            UPDATE ORDER_DETAILS SET DELIVERY_STATUS='IN_TRANSIT' WHERE ORDER_ID=1;
            UPDATE ORDER_LINE_ITEMS SET ITEM_QTY=100 WHERE ORDER_ID=1 AND LINE_ITEM_ID=1;
        END LOOP;
END;
/
COMMIT;
```

Sample output:

```json
{
  "xid": "1342848513.10.11.105432",
  "csn": "334506722",
  "tx_ts": "2025-07-07 22:34:16.000000",
  "event_count": 150000,
  "tx_complete": true,
  "data_collections": [
    {
      "data_collection": "ORDERS",
      "event_count": 50000
    },
    {
      "data_collection": "ORDER_DETAILS",
      "event_count": 50000
    },
    {
      "data_collection": "ORDER_LINE_ITEMS",
      "event_count": 50000
    }
  ]
}
```

Note: Ensure memory settings are sufficient for the JVM to handle large transactions. Example JVM options:

```properties
jvm.bootoptions=-Xms4g -Xmx6g
gg.log.level=debug
gg.report.time=30sec
```

## Library location

Libraries required for building the project were retrieved manually from Oracle GoldenGate installation. The libraries are located in the following directory:

```shell
/opt/oracle/ggbd/ggjava/resources/lib
```

## Known Issues

- **NullPointerException in metaDataChangedEvent**

```shell
ERROR 2025-07-07 22:08:48.000826 [main] - An exception occurred calling metaDataChanged in the formatter.                                                                                                         
java.lang.NullPointerException: null                                                                     
        at oracle.goldengate.format.json.JsonFormatter.metaDataChanged(JsonFormatter.java:550) ~[ggformatters-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.datasource.handler.NgFormattedOutputHandler.metaDataChanged(NgFormattedOutputHandler.java:156) [ggaddons-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.handler.kafka.KafkaHandler.metaDataChanged(KafkaHandler.java:358) [ggkafka-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.datasource.DsEventManager$6.send(DsEventManager.java:529) [ggdbutil-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.datasource.DsEventManager.distributeEvent(DsEventManager.java:120) [ggdbutil-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.datasource.DsEventManager.fireMetaDataChanged(DsEventManager.java:535) [ggdbutil-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.datasource.AbstractDataSource.fireMetaDataChanged(AbstractDataSource.java:550) [ggdbutil-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.datasource.UserExitDataSource.newTableMetaData(UserExitDataSource.java:2278) [ggdbutil-21.4.0.0.0.002.jar:21.4.0.0.0.002]
        at oracle.goldengate.datasource.UserExitDataSource.newTableMetaData(UserExitDataSource.java:2118) [ggdbutil-21.4.0.0.0.002.jar:21.4.0.0.0.002]
```

Reference:
- [Debezium Transaction Metadata](https://debezium.io/documentation/reference/stable/connectors/oracle.html#oracle-transaction-metadata)