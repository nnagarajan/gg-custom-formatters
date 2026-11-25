# gg-custom-formatters
## Overview
This repository contains `TransactionBoundaryEventJsonFormatter`, a custom formatter for the Oracle GoldenGate for Big Data Kafka handler, inspired by the Debezium transaction metadata format. For each source transaction in a Replicat delivery it publishes one marker with the transaction id (XID), commit sequence number (CSN), timestamp, whether the transaction is complete, and per table the operation count, key columns, each operation's key values and its trail position. The key fields and the positions can each be turned off (see Marker Fields).

**Most teams should start with the [recommended setup](#recommended-setup-counts-only-no-maxtransops): counts only, no `MAXTRANSOPS`.** The key and position fields and `MAXTRANSOPS` support exist for consumers that need them, at the cost of the extra caveats below.

## Recommended setup: counts only, no MAXTRANSOPS

Turn off both per-operation fields and leave `MAXTRANSOPS` unset:

```properties
# rep3.props
gg.handler.kafkahandler.format.markerKeys=false
gg.handler.kafkahandler.format.markerPos=false
```

```shell
-- rep3.prm: GROUPTRANSOPS as usual, no MAXTRANSOPS line
GROUPTRANSOPS 1000
```

Each marker then holds `xid`, `csn`, `tx_ts`, `event_count`, `tx_complete` and per table `data_collection` and `event_count`:

```json
{"xid":"1342848513.10.13.112798","csn":"344880273","tx_ts":"2026-11-23 12:31:00.000000","event_count":5,"tx_complete":true,"data_collections":[{"data_collection":"ORDERS","event_count":1},{"data_collection":"ORDER_DETAILS","event_count":1},{"data_collection":"ORDER_LINE_ITEMS","event_count":3}]}
```

What this gives:

- **Size does not depend on the transaction.** A marker is about 300 bytes for the three APPUSER tables (measured: 296 bytes for 5 operations, 312 bytes for 54,000). A Kafka message holds at most `GROUPTRANSOPS` markers (each transaction has at least one operation), so at `GROUPTRANSOPS 1000` it stays under about 300 KB. Default Kafka limits (1 MB) are enough; bulk transactions need no special sizing.
- **One marker per source transaction.** Every marker has `tx_complete: true`. A replay after an abend is also one whole transaction with the same `xid:csn`, so a consumer recognizes it as a duplicate.
- **Fewer reasons to abend.** No key resolution: tables need no primary key or key column file entry, and missing or NULL key values do not abend. The key column file is not read. A truncate or a Kafka send failure still abends.
- **What the consumer does:** match a transaction's data messages by `xid:csn` and wait until each table's count of distinct data messages (deduplicated by the data messages' own `pos`) equals the marker's `event_count`. A repeated `xid:csn` is a duplicate. See [Consumer Guide](#consumer-guide).

Limits:

- The marker does not say *which* rows changed; the consumer learns that only from the data messages.
- Replicat holds a whole source transaction in memory as one delivery (`mode=tx`); for very large transactions that is the main cost on the GoldenGate side, not the marker.
- Property names must be exact: `markerKeys` and `markerPos`. A misspelt name (for example `markerOps`) does not turn the field off; check the first marker after restarting rep3.

Choose keys, positions or `MAXTRANSOPS` only if a consumer needs per-row keys in the marker or a fixed bound on the size of each GoldenGate delivery; read the caveats below first.

## Caveats at a glance

Teams deploying this formatter or consuming its topic should know these before going live. Details are in the linked sections. The last column says whether the caveat applies to the [recommended setup](#recommended-setup-counts-only-no-maxtransops) (`markerKeys=false`, `markerPos=false`, no `MAXTRANSOPS`).

| # | Caveat | What to do | Recommended setup |
|---|---|---|---|
| 1 | Kafka orders messages only within a partition. With no `keyMappingTemplate`, markers have a `null` key and can spread over partitions. | Fixed key or a 1-partition topic ([7. Kafka Producer Settings](#7-kafka-producer-settings)). | Applies |
| 2 | A split transaction (`MAXTRANSOPS`) arrives as several markers with the same `xid:csn`; only the last has `tx_complete: true`. | Consumers merge by `xid:csn` until `tx_complete: true` ([Consumer Guide](#consumer-guide)). | No: never split |
| 3 | After an abend in the middle of a split transaction, Replicat replays it as **one whole delivery**. GoldenGate has no parameter to make `MAXTRANSOPS` apply to the replay. | Size Kafka limits for the largest source transaction; consumers deduplicate by `pos` ([3. Replicat Settings](#3-replicat-settings)). | No: every delivery is already whole |
| 4 | Markers can be sent again after a restart (at-least-once). | Deduplicate by `pos`; keep `markerPos=true` whenever `MAXTRANSOPS` is used ([Consumer Guide](#consumer-guide)). | Applies: a repeated `xid:csn` is a duplicate |
| 5 | The producer checks `max.request.size` against the **uncompressed** marker. | Set it (and `buffer.memory`) above the largest whole-transaction marker ([Capacity Planning](#capacity-planning)). | No: messages stay under ~300 KB |
| 6 | Bulk actions (for example traders affirming or confirming many trades at once) are single large transactions: 50,000 trades ≈ 150,000 operations ≈ 6–12 MB as one marker. | Use `MAXTRANSOPS`, size Kafka for the largest bulk, and ask whether the application can commit bulk actions in chunks ([Capacity Planning](#capacity-planning)). | No for the marker (~300 bytes); the data messages and the consumer still see 150,000 operations |
| 7 | `key_ids` come from the after image. A key column that changes on update (such as `VERSION`) shows its **new** value. | Include such columns only if consumers match on them; log them with `ADD TRANDATA` ([5. Key Columns](#5-key-columns)). | No: no keys |
| 8 | Any key problem (missing or NULL value, bad mapping), a truncate, or a Kafka send failure abends Replicat; nothing is published for that delivery. | Test the key column file before deploying; follow [6. Abend Rules and Recovery](#6-abend-rules-and-recovery). | Truncates and Kafka failures only |
| 9 | The formatter's INFO/DEBUG log lines do not appear in GoldenGate's default logging; only ERROR lines reach the report file. | Add a `com.technext` logger if you need them ([3. Replicat Settings](#3-replicat-settings)). | Applies |
| 10 | Markers count what rep3 delivers; the data messages are what the data Replicat (for example `kc`) delivers. If the two differ (one has `IGNOREDELETES`, a `FILTER`/`WHERE`, a different `MAP`, or was repositioned with `ALTER REPLICAT ... BEGIN`/`EXTSEQNO`), counts never match and a consumer that waits for them waits forever. | Give rep3 and the data Replicat the same trail, table list and operation filters; reposition both together ([3. Replicat Settings](#3-replicat-settings)). | Applies |
| 11 | While rep3 is stopped or abended, no markers are published. A consumer that only alerts on a transaction waiting for its data sees nothing wrong: output just stops. | Monitor rep3 itself (`INFO`/`LAG REPLICAT rep3`) or alert when no marker arrives for N minutes while data topics keep receiving messages. | Applies |

## Contract: one marker per source transaction per delivery

With `gg.handler.kafkahandler.mode=tx` the Kafka handler sends **one Kafka message per Replicat delivery**. The formatter groups the delivery's operations by each operation's own source transaction (`xid:csn`), in trail order, and writes one marker per group. Downstream consumers (the Spark pipeline in `transactional-cdc-processing-spark`) rely on the counts, keys and positions (whichever are turned on, see 4. Marker Fields) being exact.

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

**Consumers** must merge markers with the same `xid:csn` until one has `tx_complete: true`, and deduplicate operations by `pos` (after a Replicat restart an already published delivery can be sent again; with `markerPos=false` see 4. Marker Fields). Without `MAXTRANSOPS` (the recommended setup) every marker has `tx_complete: true`, each `xid:csn` has one marker, and a repeated `xid:csn` is a duplicate.

After an abend, Replicat replays a split transaction as **one whole delivery**, so a consumer can receive some fragments (`tx_complete: false`) and then a single `tx_complete: true` marker that lists **all** the transaction's operations again, including those already received. Deduplicating by `pos` handles this; a consumer must not add `event_count`s of markers with the same `xid:csn` without deduplicating.

## Message Format

Examples below use the default settings (`markerKeys=true`, `markerPos=true`); see 4. Marker Fields for the other shapes.

One source transaction (1 order with 1 detail row and 2 line items, keys as in the sample key column file below):

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
      "key_columns": ["ORDER_ID", "DETAIL_ID"],
      "key_ids": [["101", "9"]],
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

Each `data_collections` entry lists every operation on that table, in trail order (each field is present unless turned off, see Marker Fields):

| Field | Content |
|---|---|
| `key_columns` | The table's key column names, in key order: the primary key from GoldenGate metadata, or the table's entry in the key column file. |
| `key_ids` | One array of key values per operation, in `key_columns` order. Values are strings. A single-column key is still wrapped: `[["101"]]`. |
| `pos` | One trail position per operation: 10-digit trail sequence number followed by 10-digit byte offset (`00000000010000004321` = sequence 1, offset 4321). This is the same `pos` the standard GoldenGate JSON formatter writes on data messages. |

- `key_ids[i]` and `pos[i]` describe the same operation, and both arrays have `event_count` entries.
- A row changed twice in one transaction appears twice in `key_ids` (with two different `pos` values).
- Inserts and updates take key values from the after image, falling back to the before image (compressed updates often carry the key only there). Deletes take them from the before image. Primary keys are assumed never to change. A mapped column that does change on update, such as a `VERSION` used for optimistic locking, is reported with its **new** (after-image) value; that is what consumers matching on (id, version) against the data messages need, but it means the same row appears with different `key_ids` across transactions.
- Consumers can match a marker to its data messages by `pos`, and should deduplicate by `pos`: after a Replicat restart, an already published delivery can be sent again.

Message size: a marker is about 200 bytes plus about 35–80 bytes per operation (about 23 for `pos`, the rest for key values: about 12 for a short numeric key, about 60 for a UUID plus timestamp). 1,000 operations add about 38 KB with numeric keys or about 79 KB with wide keys. The broker's default `max.message.bytes` is about 1 MB. `MAXTRANSOPS` bounds the operations per delivery in normal running (but not when a transaction is replayed after an abend, see Replicat Settings), so it bounds the message: at `MAXTRANSOPS 1000` a message stays under about 80 KB of keys and positions, plus about 200 bytes per grouped transaction (about 400 KB in the worst case at `GROUPTRANSOPS 1000`). Without `MAXTRANSOPS`, one source transaction of roughly 25,000 operations with numeric keys (13,000 with wide keys) reaches 1 MB. With `markerKeys=false` a marker is about 200 bytes plus about 23 bytes per operation (1 MB at roughly 45,000 operations); with both flags off it is about 200 bytes plus about 50 bytes per table, whatever the operation count. Measured figures, daily volume and bulk-transaction sizes are in [Capacity Planning](#capacity-planning).

## Configuration

### 1. Kafka Handler Integration
To use the custom formatter with the GoldenGate Kafka handler, set the formatter class in the handler properties (`rep3.props`). The handler must run in transaction mode:
```properties
gg.handler.kafkahandler.format=com.technext.goldengate.dasource.formatter.TransactionBoundaryEventJsonFormatter
gg.handler.kafkahandler.mode=tx
```
### 2. Custom Library Directory
Build the jar (`mvn clean package`, see Library location) and copy it to a directory for custom libraries:

```shell
mkdir -p /opt/oracle/ggbd/ggjava/resources/customlib
cp target/gg-custom-formatters-1.0-SNAPSHOT.jar /opt/oracle/ggbd/ggjava/resources/customlib/
```
Update the classpath in your configuration as follows:

```properties
gg.classpath=dirprm/:/var/lib/kafka/libs/*:/opt/oracle/ggmabd/home/opt/DependencyDownloader/dependencies/kafka_3.3.2/*:/opt/oracle/ggbd/ggjava/resources/customlib/*
```

### 3. Replicat Settings
Below is an example of a replicat parameter files (`replicat.prm`):

```shell
REPLICAT rep3
TARGETDB LIBFILE libggjava.so SET property=/opt/oracle/ggbd/dirprm/rep3.props
REPORTCOUNT EVERY 15 SECONDS, RATE
GROUPTRANSOPS 1000
-- No MAXTRANSOPS in the recommended setup (see below)
MAP ORCLPDB.APPUSER.ORDERS, TARGET APPUSER.ORDERS;
MAP ORCLPDB.APPUSER.ORDER_DETAILS, TARGET APPUSER.ORDER_DETAILS;
MAP ORCLPDB.APPUSER.ORDER_LINE_ITEMS, TARGET APPUSER.ORDER_LINE_ITEMS;
```

> **Same operations as the data Replicat.** Consumers compare each marker's counts with the data messages, which another Replicat (for example `kc`) publishes from the same trail. Both must deliver the same operations for these tables: no `IGNOREDELETES`, `IGNOREUPDATES`, `FILTER` or `WHERE` on one but not the other, and the same tables mapped. Reposition them together (`ALTER REPLICAT ... BEGIN`/`EXTSEQNO`). A difference leaves a consumer waiting for operations that never arrive, or receiving more than the marker declares. A `COLMAP` on rep3 is fine: it changes columns, not operations.
>
> **`MAXTRANSOPS` is supported but not recommended.** The recommended setup (counts only) leaves it unset: markers stay small whatever the transaction size, so there is nothing to bound. Use it only together with `markerPos=true` and a consumer that merges fragments; the rest of this note applies to that case.
>
> It splits a large source transaction across deliveries; each fragment gets its own marker with `tx_complete: false` until the last one. It keeps every message within a fixed size (see Message Format), except after an abend (below).
>
> **Abend while a split transaction is in progress:** on restart, Replicat replays the interrupted source transaction as **one** delivery; `MAXTRANSOPS` is not applied to it. Observed on rep3 (`GROUPTRANSOPS 1000`, `MAXTRANSOPS 2`): a 550-operation transaction was delivered in fragments of 2 (`state=MIDDLE`), the formatter abended on the first one, and after the restart the same transaction arrived as one delivery (`ops=550, state=WHOLE`) and was published as a single marker with `tx_complete: true`. Two consequences:
> - The replayed marker can be as large as the whole transaction. Size the Kafka limits (`max.request.size`, topic `max.message.bytes`, consumer fetch sizes) for the largest source transaction you expect, not for `MAXTRANSOPS`; otherwise the replay itself fails with a record-too-large error.
> - Its operations can overlap fragments already published before the abend. Consumers deduplicate by `pos` (keep `markerPos=true`).
>
> **No GoldenGate setting changes this.** The Oracle documentation has no Replicat parameter that makes `MAXTRANSOPS` apply on recovery. `REPERROR` (`TRANSABORT`, `RETRYOP`, ...) governs database apply errors; a formatter exception is a Java error (`OGG-15051`) and always abends. For `GROUPTRANSOPS`/`BATCHSQL`, Oracle documents that Replicat replays a failing transaction "using the same transaction boundaries as the source transaction", which matches what rep3 does. Splitting inside the formatter is not possible either: in `mode=tx` the Kafka handler sends one message per delivery, and in `mode=op` the formatter is never told where Replicat checkpoints, so holding operations back would lose them on a crash. A guaranteed size bound would need a custom handler that splits each delivery itself before Replicat checkpoints. Not yet tested: whether a restart after some fragments were already committed resumes in the middle of the transaction or replays it whole.
>
> **Rollout order:** deploy this formatter with `MAXTRANSOPS` still commented out (the new fields are additive), update the consumer to merge fragments by `xid:csn` and deduplicate by `pos`, then enable `MAXTRANSOPS` and restart rep3.
>
> **Verified on rep3 (GoldenGate for Big Data 21.4, 2026-11-23):**
> - `MAXTRANSOPS 2` splits a 550-operation transaction into 275 deliveries (`state=MIDDLE` ... `END` in `dirrpt/REP3.log`); the markers say `tx_complete: false` ... `true`.
> - `gg.handler.kafkahandler.format.<name>` properties reach the formatter (`Setting property on '...TransactionBoundaryEventJsonFormatter'` in `REP3.log`).
> - An exception from the formatter abends Replicat (`OGG-15051`, `OGG-01668`) and nothing is published for that delivery; an invalid flag value (a stray character, as in ``markerKeys=false` ``) abends at startup.
>
> **Formatter log lines:** GoldenGate's default logging (`ggjava/resources/classes/log4j-default.properties`) writes only `oracle.goldengate` loggers to `dirrpt/REP3.log`; other loggers, including this formatter's (`com.technext...`), reach only the report file (`REP3.rpt`), at ERROR level. Abend messages therefore appear in `REP3.rpt`, but INFO lines such as `Loaded key column file ...` do not appear anywhere. To see them, copy `log4j-default.properties` to `dirprm/log4j2-rep3.properties`, add
>
> ```properties
> logger.technext.name=com.technext
> logger.technext.level=info
> logger.technext.appenderRef.rolling.ref=rollingAppender
> ```
>
> and point the JVM at it (not yet tried on rep3): `jvm.bootoptions=-Xms4g -Xmx6g -Dlog4j.configurationFile=/opt/oracle/ggbd/dirprm/log4j2-rep3.properties`.

Example Kafka handler properties (`rep3.props`):

```properties
gg.handlerlist=kafkahandler
gg.handler.kafkahandler.type=kafka
gg.handler.kafkahandler.kafkaProducerConfigFile=kp.properties
#All markers go to one fixed topic
gg.handler.kafkahandler.topicMappingTemplate=dev.transaction_metadata_json
#One fixed message key, so all markers go to one partition and stay in trail order
gg.handler.kafkahandler.keyMappingTemplate=transaction_metadata
gg.handler.kafkahandler.mode=tx
gg.handler.kafkahandler.transactionsEnabled=false
gg.handler.kafkahandler.format=com.technext.goldengate.dasource.formatter.TransactionBoundaryEventJsonFormatter
#Marker fields: counts only (recommended setup); both default to true (see 4. Marker Fields)
gg.handler.kafkahandler.format.markerKeys=false
gg.handler.kafkahandler.format.markerPos=false
#Only with markerKeys=true: key columns other than the primary key (see 5. Key Columns)
#gg.handler.kafkahandler.format.keyColumnsFile=dirprm/key_columns.properties
#Sample gg.classpath for Apache Kafka
gg.classpath=dirprm/:/var/lib/kafka/libs/*:/opt/oracle/ggmabd/home/opt/DependencyDownloader/dependencies/kafka_3.3.2/*:/opt/oracle/ggbd/ggjava/resources/customlib/*
javawriter.stats.full=TRUE
javawriter.stats.display=TRUE

#Logging and JVM
gg.log=log4j
gg.log.level=debug
gg.report.time=30sec
jvm.bootoptions=-Xms4g -Xmx6g

```
### 4. Marker Fields

Teams can choose which per-operation fields the markers carry: keys, positions, both (the default), or neither. Neither, without `MAXTRANSOPS`, is the [recommended setup](#recommended-setup-counts-only-no-maxtransops).

| Property | Default | `false` leaves out |
|---|---|---|
| `gg.handler.kafkahandler.format.markerKeys` | `true` | `key_columns` and `key_ids` |
| `gg.handler.kafkahandler.format.markerPos` | `true` | `pos` |

```properties
#Positions only
gg.handler.kafkahandler.format.markerKeys=false
gg.handler.kafkahandler.format.markerPos=true
```

| `markerKeys` | `markerPos` | Each `data_collections` entry |
|---|---|---|
| `true` | `true` | `data_collection`, `event_count`, `key_columns`, `key_ids`, `pos` |
| `true` | `false` | `data_collection`, `event_count`, `key_columns`, `key_ids` |
| `false` | `true` | `data_collection`, `event_count`, `pos` |
| `false` | `false` | `data_collection`, `event_count` |

- Turned-off fields are left out, not written as empty arrays. The marker-level fields (`xid`, `csn`, `tx_ts`, `event_count`, `tx_complete`, `data_collections`) are always present.
- With `markerKeys=false` the formatter does not resolve keys at all: tables need no primary key or key column file entry, missing or NULL key values do not abend, and the key column file is not read, even if `keyColumnsFile` is set (a missing or malformed file then does not stop Replicat). Truncates still abend.
- Values are `true` or `false`, case-insensitive. Any other value stops Replicat at startup (`Invalid value for format.markerKeys: 'no' (expected true or false)`).
- Without `pos`, consumers cannot deduplicate a delivery that is sent again after a Replicat restart. Without `MAXTRANSOPS` each `xid:csn` has one marker, so a repeated `xid:csn` is a duplicate. **With `MAXTRANSOPS`, keep `markerPos=true`**: fragments of one transaction share its `xid:csn`, so without `pos` a re-sent fragment cannot be told apart from the next one, and a whole-transaction replay after an abend cannot be reconciled with the fragments already received.
- The names are exact. A misspelt property (for example `markerOps`) does not set either flag, so that field stays on; check the first marker after restarting rep3.
- Read at startup; restart rep3 after changing them. Changing them changes the marker shape, so update consumers first.

Positions only (`markerKeys=false`), same transaction as the first Message Format example:

```json
{
  "xid": "1342848513.8.14.5716",
  "csn": "344374437",
  "tx_ts": "2026-11-07 21:24:36.000000",
  "event_count": 4,
  "tx_complete": true,
  "data_collections": [
    {"data_collection": "ORDERS", "event_count": 1, "pos": ["00000000010000004321"]},
    {"data_collection": "ORDER_DETAILS", "event_count": 1, "pos": ["00000000010000004587"]},
    {"data_collection": "ORDER_LINE_ITEMS", "event_count": 2, "pos": ["00000000010000004853", "00000000010000005119"]}
  ]
}
```

### 5. Key Columns

Applies when `markerKeys=true` (the default).

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
- The file is read once at startup (in the formatter's `init`, after all properties are set), and only when `markerKeys=true`. A missing or unreadable file stops Replicat with an error naming the file; a malformed line (no `=`, empty table or column name, repeated column, table listed twice) stops it with an error naming the file and line. Restart rep3 after editing it.
- Every mapped column must be captured for updates as well as inserts, which needs supplemental logging on the source for those columns and their before images in the trail:

  ```
  ADD TRANDATA APPUSER.ORDER_LINE_ITEMS COLS (ORDER_ID, LINE_NO)
  ```

  plus `LOGALLSUPCOLS` in the Extract parameter file.
- A column that changes on update (for example `VERSION`) can be mapped, for instance to match markers to data messages on (id, version). `key_ids` then holds its after-image (new) value, so one row has different `key_ids` in different transactions. Include it in `ADD TRANDATA ... COLS (...)`, or an update that does not set it abends with `Key column VERSION is MISSING`.
- Test the file against the real tables before deploying: a column the table does not have abends Replicat on the first operation for that table (seen on rep3 with `ORDER_DETAILS=ORDER_ID,DETAIL_ID`).

### 6. Abend Rules and Recovery

The formatter never writes a marker it cannot fill in exactly. In these cases it throws, nothing is written for that delivery, and Replicat abends:

| Cause | Error (example) |
|---|---|
| A key column is not in the record (neither image) | `Key column LINE_NO is MISSING for APPUSER.ORDER_LINE_ITEMS op=DO_UPDATE_FIELDCOMP pos=00000000010000004587 xid=X1 csn=500` |
| A key column's value is NULL | `Key column LINE_NO is NULL for APPUSER.ORDER_LINE_ITEMS op=DO_INSERT pos=... xid=... csn=...` |
| A truncate reaches the formatter | `Unsupported operation DO_TRUNCATE on APPUSER.ORDERS pos=...: truncates must not be captured (GETTRUNCATES)` |
| A mapped column does not exist in the table | `Key column file maps APPUSER.ORDER_LINE_ITEMS to [ORDER_ID, LINE_NUM], but APPUSER.ORDER_LINE_ITEMS has no column LINE_NUM` |
| A table has no primary key and no mapping | `Table APPUSER.X has no primary key and no key column mapping; add it to the key column file (...)` |
| The key column file is missing or unreadable (at startup) | `Cannot read key column file dirprm/key_columns.properties: ...` |
| The key column file has a malformed line (at startup) | `Malformed key column file dirprm/key_columns.properties line 2: ...` |
| `markerKeys` or `markerPos` is not `true` or `false` (at startup) | `Invalid value for format.markerKeys: 'no' (expected true or false)` |

With `markerKeys=false`, only the truncate and invalid-value rows apply.

A mapped column that is nullable only logs a warning on first sight of the table (`Key column APPUSER.ORDER_LINE_ITEMS.LINE_NO is nullable; a NULL value in it will abend Replicat`).

Extract must not capture truncates for these tables (no `GETTRUNCATES`).

**Recovery.** A missing or NULL key is already in the trail, so restarting rep3 abends again at the same position. Fixing supplemental logging only helps records captured afterwards. To get past it, either:

1. change or remove the table's entry in the key column file so the record has a key, then restart rep3;
2. skip the source transaction (`START REPLICAT rep3 SKIPTRANSACTION`); its marker is lost, so tell the consumer; or
3. fix supplemental logging, re-capture that range, and reposition rep3; or
4. set `markerKeys=false` and restart rep3. Markers then carry no keys at all, so do this only if the consumer accepts that shape.

### 7. Kafka Producer Settings

**Ordering.** Kafka keeps order only within a partition. The Kafka handler sets the message key from `keyMappingTemplate`; if it is unset (as it was on rep3: the console showed `null` keys), markers can be spread over the topic's partitions and a consumer can see a later fragment before an earlier one. Either give every marker the same key (shown in the `rep3.props` example above) or create the topic with one partition; both is safest. The marker topic has low throughput, so one partition is enough.

Recommended `kp.properties`:

```properties
bootstrap.servers=...
value.serializer=org.apache.kafka.common.serialization.ByteArraySerializer
key.serializer=org.apache.kafka.common.serialization.ByteArraySerializer

#Order and no duplicates within a producer session
acks=all
enable.idempotence=true
max.in.flight.requests.per.connection=5
retries=2147483647
delivery.timeout.ms=300000
request.timeout.ms=30000
reconnect.backoff.ms=1000
reconnect.backoff.max.ms=10000

#Size: above the largest whole-transaction marker (see Capacity Planning)
max.request.size=16777216
buffer.memory=67108864
compression.type=lz4

batch.size=16384
linger.ms=0
```

- `enable.idempotence=true` with at most 5 in-flight requests keeps order across retries and prevents duplicates from retries. Keep it explicit: if the broker or ACLs do not allow it, the producer fails instead of silently dropping the guarantee. If idempotence must be off, set `max.in.flight.requests.per.connection=1`.
- `max.request.size` is checked against the **uncompressed** record, so it must exceed the largest marker. The topic's `max.message.bytes` (and the broker's `replica.fetch.max.bytes`) apply to the compressed batch; consumers need `max.partition.fetch.bytes` and `fetch.max.bytes` at least as large. A marker over any of these abends Replicat with a record-too-large error, and the replay hits the same error.
- Idempotence covers one producer session only. After a Replicat restart, deliveries can be sent again; consumers deduplicate by `pos`. Kafka transactions (`transactionsEnabled`) do not help, because GoldenGate's checkpoint is not committed together with Kafka.
- The handler flushes the producer on every delivery commit, so markers are published in trail order. There is no ordering between this topic and the data topics; match markers to data messages by `pos`, not by arrival time.

## Consumer Guide

### Counts only, no MAXTRANSOPS (recommended setup)

Each source transaction has exactly one marker, with `tx_complete: true`, so there is nothing to merge:

- **Completeness.** A transaction is complete when, for each table in `data_collections`, the consumer holds `event_count` distinct data messages with that `xid:csn`. Deduplicate data messages by their own `pos` (the data Replicat writes it on every message; it is independent of `markerPos`). More data messages than declared means rep3 and the data Replicat deliver different operations (caveat 10): stop and investigate rather than emit.
- **Duplicate markers.** After a rep3 restart a marker, or a whole grouped message, can arrive again. Its `xid:csn` is then already known: ignore it. Markers arrive in commit order on one partition, so a consumer can keep the last completed `csn` (compared as a number) plus the `xid`s completed at that `csn`, and drop any marker at or below it; two transactions can commit with the same `csn`, which is why the `xid` is kept.
- **Order.** Emit transactions in marker order (Kafka offset, then position in a grouped message), which is commit order. Data topics are not ordered relative to the marker topic; wait for counts, not for arrival time.
- **Stuck transactions.** A transaction whose data never completes (data Replicat down or lagging, a data message dropped as unusable, caveat 10) holds back every later one if the consumer emits in strict order. Alert when one has waited longer than normal bulk-load lag (for example 10–30 minutes), and alert separately when no marker arrives at all (caveat 11).

### Keys, positions or MAXTRANSOPS

**Completeness.** Collect markers by `xid:csn` until one has `tx_complete: true`. Fragments arrive in order on one partition (see 7. Kafka Producer Settings), and Replicat never skips a fragment it has not published, so at that point every operation of the transaction has been received. Only committed transactions reach the trail, so each fragment is already committed data; if the business does not need to act on a transaction as a whole, fragments can be processed as they arrive.

**Duplicates.** They come from Replicat restarts (a fragment or delivery sent again) and from the whole-transaction replay after an abend (one `tx_complete: true` marker that repeats operations already received). Producer retries do not cause duplicates when idempotence is on. Deduplicate by `pos`:

- `pos` is a 20-digit zero-padded trail position, so string comparison gives trail order, and markers on one partition arrive in trail order. A high-water mark therefore works:

  ```text
  state: watermark (highest pos fully processed), open[xid:csn] -> ops keyed by pos

  for each marker, in partition order:
    ops = (key_ids[i], pos[i]) of every data_collection where pos[i] > watermark
    open[xid:csn].putAll(ops)                     # a repeated pos adds nothing
    if marker.tx_complete:
        tx = open.remove(xid:csn)
        process(tx)                               # each operation once
        watermark = max(watermark, max pos in tx)
        commit(output, watermark)                 # atomically with the consumer's output
  ```

- Do **not** deduplicate by `xid:csn` (every fragment shares it), `event_count` (a replay counts more), or `key_ids` (one row changed twice has one key and two positions).
- The watermark only holds within one trail. If Replicat is moved to a different or re-created trail, positions restart and the watermark must be reset; a safer alternative is a set of seen `xid:csn`+`pos` kept for a retention window.
- Matching markers to data messages by `pos` works only if the data messages come from the same trail (`dirdat/e4` for rep3).
- With `markerPos=false` none of this is possible; then run without `MAXTRANSOPS` and treat a repeated `xid:csn` as a duplicate (see the counts-only section above). A replayed grouped message is detected the same way, marker by marker.

## Capacity Planning

Sizes below were measured on generated markers with the shape rep3 produces (keys `ORDER_ID,VERSION` and `ORDER_ID,LINE_ITEM_ID,VERSION`, 5-digit ids, compact JSON).

**Per operation.** Large transactions cost about 42 bytes per operation with keys and positions (about 19 with keys only, 23 with positions only). Small transactions cost more per operation, because every marker repeats the transaction header, table names and key column names: a one-order transaction (1 order, 1 detail, about 3.5 line items, about 5.4 operations) is about 700 bytes, or 131 bytes per operation. UUID-style keys roughly double the key share.

**One transaction of 10,000 orders** (10,000 orders, 10,000 details, about 35,000 line items, about 55,000 operations):

| `markerKeys` | `markerPos` | Marker | gzip |
|---|---|---|---|
| true | true | ~2.1 MB | ~330 KB |
| true | false | ~0.95 MB | ~140 KB |
| false | true | ~1.2 MB | ~190 KB |
| false | false | ~0.4 KB | — |

**Counts only** (`markerKeys=false`, `markerPos=false`): about 300 bytes per marker for the three APPUSER tables, whatever the operation count (measured 296 bytes for 5 operations, 312 bytes for 54,000), plus about 50 bytes per additional table. At `GROUPTRANSOPS 1000` a Kafka message holds at most 1,000 markers, about 300 KB. For 3.5 million operations per day this is about 200 MB/day uncompressed with small (one-order) transactions and about 2 MB/day with transactions of 100 orders or more.

**Daily volume for 3.5 million operations**, keys and positions on:

| Typical transaction | Markers/day | Uncompressed/day | Compressed/day |
|---|---|---|---|
| Small (one order, ~5.4 ops) | ~650,000 | ~460 MB | ~120–180 MB |
| Medium or large (≥ 100 orders) | ≤ 6,500 | ~150 MB | ~22 MB |

Disk = daily volume × replication factor × retention days, plus Kafka record overhead (about 20–70 bytes per record; with `GROUPTRANSOPS` several small transactions share one record). Example: small transactions, replication factor 3, 7 days ≈ 9.6 GB uncompressed, 0.5–3.8 GB compressed.

**Transaction sizes.** Typical OLTP transactions change 1–10 rows (TPC-C: about 23 for an order entry, 4 for a payment). The order-entry transactions on rep3 are about 5.5 operations. Measure your own distribution from the topic, merging fragments by `xid:csn`:

```shell
kafka-console-consumer --bootstrap-server ... --topic dev.transaction_metadata_json \
  --from-beginning --timeout-ms 10000 2>/dev/null |
jq -c 'if type=="array" then .[] else . end | {k:(.xid+":"+.csn), n:.event_count}' |
jq -s 'group_by(.k) | map(map(.n)|add) | sort | {txs:length, avg:(add/length), median:.[length/2|floor], p99:.[(length*0.99)|floor], max:.[-1]}'
```

**Bulk actions.** A trader affirming or confirming many trades at once is usually one database transaction changing 2–4 rows per trade (status/version update, allocations, audit or outbox inserts). At 3 rows per trade:

| Bulk | Operations | Whole marker (numeric / UUID keys) | Fragments at `MAXTRANSOPS 5000` |
|---|---|---|---|
| 500 trades | 1,500 | ~63 KB / ~120 KB | 1 |
| 5,000 trades | 15,000 | ~0.6 MB / ~1.2 MB | 3 × ~210–400 KB |
| 50,000 trades | 150,000 | ~6.3 MB / ~12 MB | 30 × ~210–400 KB |

- Set `MAXTRANSOPS` (5,000–10,000) so normal fragments stay at a few hundred KB; typical OLTP transactions are never split.
- Size `max.request.size`, `buffer.memory` and the topic and consumer limits for the **largest bulk**, because a replay after an abend is one whole marker (for example 16 MB for 50,000 trades).
- Consumers hold a split bulk transaction until `tx_complete: true` (about 150,000 entries for 50,000 trades).
- Replicat holds a whole delivery in memory in `mode=tx`; on a replay that is the whole bulk transaction (a few hundred MB for 150,000 operations with only the mapped columns).
- Where a bulk action does not have to be atomic, committing it in chunks (for example 500 trades per commit) in the application removes the problem at the source.

## Testing

> The sample outputs below were recorded before `key_columns`, `key_ids` and `pos` existed. They match the shape with `markerKeys=false` and `markerPos=false`. With the default settings each `data_collections` entry also carries those three fields, with one `key_ids` and one `pos` entry per operation (for example 50 each for `ORDERS` in Example 1).

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

Pressure test with 150,000 updates to the same transaction.

> With `key_ids` and `pos`, a single marker for this transaction is about 5 MB (roughly 35 bytes per operation), which exceeds the default 1 MB Kafka message limit. Run it with `MAXTRANSOPS` set (for example `MAXTRANSOPS 10000`): the transaction is then delivered as 15 fragments of 10,000 operations (about 350 KB each), with `tx_complete: false` on the first 14 and `true` on the last. If Replicat abends part-way, the replay is one 5 MB marker, so the Kafka limits must allow it (see Replicat Settings). With `markerKeys=false` it is about 3.5 MB, still over the limit; with both flags off it is the single small marker shown below. The output below is from before keys and positions were added.

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

`pom.xml` reads them from that path (`lib.location`). Build with `mvn clean package`; the tests need no running GoldenGate.

## Known Issues

- **NullPointerException in metaDataChangedEvent** (resolved): GoldenGate's `JsonFormatter.metaDataChanged` throws this when called on this formatter. The formatter overrides `metaDataChanged` without calling it (it only clears its cached key columns), so the error no longer occurs. The original log:

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