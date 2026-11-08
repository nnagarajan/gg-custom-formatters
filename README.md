# gg-custom-formatters
## Overview
This repository contains custom formatters for Oracle GoldenGate that are inspired by the Debezium transaction metadata format. These formatters are designed to provide a structured representation of transactions, including commit sequence numbers (CSN), transaction IDs (XID), timestamps, and event counts.

## Contract: one marker per source transaction

The formatter emits **exactly one marker per source transaction (`xid:csn`)**, whose `event_count` and per-table counts equal that transaction's operations. Downstream consumers (the Spark pipeline in `transactional-cdc-processing-spark`) rely on these counts being exact.

With `GROUPTRANSOPS` (default 1000), Replicat accumulates whole source transactions into one delivered transaction. GoldenGate never splits a source transaction, but one delivery can hold several. With `gg.handler.kafkahandler.mode=tx` the Kafka handler sends **one Kafka message per delivery**. The formatter therefore groups the delivered operations by each operation's own `xid:csn`, in trail order:

| Delivery holds | Kafka message |
|---|---|
| One source transaction | A single JSON object (unchanged from earlier versions) |
| Several source transactions | A JSON array of marker objects, in commit order |

- Two transactions with the same `csn` but different `xid` are separate markers.
- In the single-object form, `xid`, `csn` and `tx_ts` come from the delivered transaction (`tx_ts` is its begin time).
- In the array form, each marker has its own `xid` and `csn`, and `tx_ts` is the timestamp of that transaction's first operation.
- `data_collections` lists tables (short names) in the order they first appear.
- `tx_ts` is a string.

## Message Format

One source transaction:

```json
{
  "xid": "1342848513.8.14.5716",
  "csn": "344374437",
  "tx_ts": "2026-11-07 21:24:36.000000",
  "event_count": 550,
  "data_collections": [
    { "data_collection": "ORDERS", "event_count": 100 },
    { "data_collection": "ORDER_DETAILS", "event_count": 100 },
    { "data_collection": "ORDER_LINE_ITEMS", "event_count": 350 }
  ]
}
```

A grouped delivery of T1 (800 operations) and T2 (500 operations):

```json
[
  {
    "xid": "X1",
    "csn": "500",
    "tx_ts": "2026-11-07 21:24:36.000000",
    "event_count": 800,
    "data_collections": [
      { "data_collection": "ORDERS", "event_count": 100 },
      { "data_collection": "ORDER_DETAILS", "event_count": 100 },
      { "data_collection": "ORDER_LINE_ITEMS", "event_count": 600 }
    ]
  },
  {
    "xid": "X2",
    "csn": "600",
    "tx_ts": "2026-11-07 21:24:37.000000",
    "event_count": 500,
    "data_collections": [
      { "data_collection": "ORDERS", "event_count": 50 },
      { "data_collection": "ORDER_DETAILS", "event_count": 50 },
      { "data_collection": "ORDER_LINE_ITEMS", "event_count": 400 }
    ]
  }
]
```

Message size: one marker is about 180–280 bytes regardless of how many operations it counts. A grouped message holds at most about `GROUPTRANSOPS` markers (about 276 KB uncompressed at 1000), which is under the broker's default `max.message.bytes` (about 1 MB). If you raise `GROUPTRANSOPS` well above 1000, raise the broker and producer message limits to match.

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

> **`MAXTRANSOPS` must stay unset.** It splits a large source transaction across several deliveries, so the formatter would emit partial markers whose counts never match the source transaction. Splitting is not supported. Keep the `--MAXTRANSOPS` line commented out. `GROUPTRANSOPS` is supported: it only groups whole source transactions.

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
## Testing

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