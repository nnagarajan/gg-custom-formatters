# gg-custom-formatters
Goldengate Transactional Formatter Inspired by Debezium Transaction Metadata

```json
{
  "csn": "1234551111",
  "xid": "5.6.641",
  "tx_ts": 1486500577691,
  "event_count": 2,
  "data_collections": [
    {
      "data_collection": "ORCLPDB1.CUSTOMER",
      "event_count": 1
    },
    {
      "data_collection": "ORCLPDB1.ORDER",
      "event_count": 1
    }
  ]
}
```

Reference:
- [Debezium Transaction Metadata](https://debezium.io/documentation/reference/stable/connectors/oracle.html#oracle-transaction-metadata)