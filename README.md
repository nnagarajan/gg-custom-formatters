# gg-custom-formatters
## Overview
This repository contains custom formatters for Oracle GoldenGate that are inspired by the Debezium transaction metadata format. These formatters are designed to provide a structured representation of transactions, including commit sequence numbers (CSN), transaction IDs (XID), timestamps, and event counts.

## Transaction Event Format
The formatter produces JSON output with the following structure:

```json
{
  "csn": "1234551111",
  "xid": "5.6.641",
  "tx_ts": 1486500577691,
  "event_count": 2,
  "data_collections": [
    {
      "data_collection": "EMPLOYEE",
      "event_count": 1
    },
    {
      "data_collection": "EMPLOYEE_DETAILS",
      "event_count": 1
    }
  ]
}
```
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
MAP ORCLPDB.APPUSER.EMPLOYEE, TARGET APPUSER.EMPLOYEE;
MAP ORCLPDB.APPUSER.EMPLOYEE_DETAILS, TARGET APPUSER.EMPLOYEE_DETAILS;
```

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
		UPDATE EMPLOYEE SET EMPLOYEE_NAME ='NAVEEN' WHERE EMPLOYEE_ID=248;
		UPDATE EMPLOYEE_DETAILS SET PNUMBER ='PNUMBER1' WHERE EMPLOYEE_ID=248;
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
  "event_count": 100,
  "data_collections": [
    {
      "data_collection": "EMPLOYEE",
      "event_count": 50
    },
    {
      "data_collection": "EMPLOYEE_DETAILS",
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
		UPDATE EMPLOYEE SET EMPLOYEE_NAME ='NAVEEN' WHERE EMPLOYEE_ID=248;
		UPDATE EMPLOYEE_DETAILS SET PNUMBER ='PNUMBER1' WHERE EMPLOYEE_ID=248;
	END LOOP;	
	UPDATE DEPARTMENT SET DEPARTMENT_NAME ='SALES' WHERE DEPARTMENT_ID=10;
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
      "data_collection": "EMPLOYEE",
      "event_count": 50
    },
    {
      "data_collection": "EMPLOYEE_DETAILS",
      "event_count": 50
    }
  ]
}
```

### Example 3

Pressure test with 100,000 updates to the same transaction

```oracle
SET TIMING ON;
BEGIN
    FOR I IN  1..50000
        LOOP
            UPDATE EMPLOYEE SET EMPLOYEE_NAME ='NAVEEN' WHERE EMPLOYEE_ID=248;
            UPDATE EMPLOYEE_DETAILS SET PNUMBER ='PNUMBER1' WHERE EMPLOYEE_ID=248;
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
  "event_count": 10000,
  "data_collections": [
    {
      "data_collection": "EMPLOYEE",
      "event_count": 50000
    },
    {
      "data_collection": "EMPLOYEE_DETAILS",
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