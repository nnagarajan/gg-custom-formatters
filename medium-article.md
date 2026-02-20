# Why Oracle GoldenGate Needs a Custom Transaction Metadata Formatter

## The Problem: Row-Level CDC Isn't Enough

Oracle GoldenGate is a powerful Change Data Capture (CDC) platform. It captures row-level changes from Oracle databases and streams them to downstream systems like Apache Kafka. Out of the box, GoldenGate's JSON formatter emits one event per row operation — an INSERT here, an UPDATE there — each as an independent JSON message.

This works fine for simple pipelines. But what happens when your downstream consumer needs to process *transactions*, not just rows?

Consider an order management system with three related tables:

- **ORDERS** — the parent order
- **ORDER_DETAILS** — shipping and fulfillment (1:1 with ORDERS)
- **ORDER_LINE_ITEMS** — individual products in the order (1:N with ORDERS)

A single business transaction — say, creating a new order — touches all three tables atomically. The database guarantees this atomicity through its transaction commit. But once GoldenGate breaks that transaction into individual row events and publishes them to separate Kafka topics, that atomicity is lost. Your downstream consumer receives a stream of disconnected events with no way to answer a fundamental question: *when have all the events for a given transaction arrived?*

## The Missing Signal

This is the core problem. Without transaction-level metadata, a streaming application that needs to join events across tables has two bad options:

1. **Use time-based windows** — wait some arbitrary duration and hope everything has arrived. This is fragile: too short and you miss late events; too long and you add unnecessary latency.

2. **Never wait** — process events individually and accept that your joins will be incomplete, producing partial or inconsistent results.

Neither option is acceptable for systems that require transactional consistency — think financial reconciliation, inventory management, or audit-grade data pipelines.

What's needed is a signal that tells the consumer: *"Transaction X touched these tables, and here's exactly how many events to expect from each."*

## The Solution: A Transaction Boundary Formatter

The [TransactionBoundaryEventJsonFormatter](https://github.com) is a custom GoldenGate formatter that emits exactly this signal. Instead of formatting individual row operations, it produces a single JSON event at each transaction's commit boundary:

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

This metadata event tells the consumer everything it needs:

- **xid** — the transaction identifier, shared by all row-level events in the same transaction
- **csn** — the commit sequence number, providing a logical ordering of transactions
- **event_count** — the total number of row events to expect
- **data_collections** — a per-table breakdown of how many events were produced

## How It Works

GoldenGate's Kafka handler operates in `tx` mode, meaning it processes operations grouped by transaction. The custom formatter hooks into this by overriding `formatOp()` but only acting on the *last* operation in the transaction:

```java
if (dsTransaction.getLastOperation().getOperationSeqno()
        == dsOperation.getOperationSeqno()) {
    return formatTransaction(dsTransaction, dsOperation);
}
```

When the last operation arrives, the formatter iterates through all operations in the transaction, aggregates counts by table name, and serializes the metadata as JSON. This event is then published to a dedicated Kafka topic (`dev.transaction_metadata_json`), separate from the row-level CDC topics.

The design is inspired by [Debezium's transaction metadata](https://debezium.io/documentation/reference/stable/connectors/oracle.html#oracle-transaction-metadata) format, which provides similar capabilities for Debezium-based CDC pipelines. By adopting a compatible structure, the formatter bridges the gap for teams using GoldenGate who want Debezium-style transactional semantics.

## Enabling Transaction-Aware Streaming

With this metadata in hand, downstream consumers can implement *transaction-aware buffering*. In a Spark Structured Streaming application, for example, the pattern looks like this:

1. Read CDC events from three Kafka topics (one per table) and the metadata topic
2. Group all events by their transaction key (`xid:csn`)
3. Buffer events in stateful processing (using `flatMapGroupsWithState` with RocksDB)
4. When the metadata event arrives for a transaction, compare expected counts against received counts
5. Only when all expected events have arrived, join them into a denormalized record and emit downstream

This gives you *transactionally consistent* output — every emitted record represents a complete, atomic transaction, with all related table changes joined together. No time-based guessing. No partial results.

## Deployment

The formatter is packaged as a JAR and deployed alongside GoldenGate's standard libraries. Configuration is minimal — a single property change in the replicat's Kafka handler configuration:

```properties
gg.handler.kafkahandler.format=com.technext.goldengate.dasource.formatter.TransactionBoundaryEventJsonFormatter
gg.handler.kafkahandler.mode=tx
gg.handler.kafkahandler.topicMappingTemplate=dev.transaction_metadata_json
```

The standard row-level replicats continue publishing CDC events to their respective topics. The transaction metadata replicat runs alongside them, reading the same trail files but producing only the metadata events.

## When You Need This

Not every CDC pipeline requires transaction-level metadata. If you're replicating a single table or your downstream consumer processes events independently, the default GoldenGate formatter works fine.

But if your pipeline involves any of the following, this formatter fills a critical gap:

- **Multi-table joins in streaming** — you need to know when all pieces of a transaction have arrived
- **Exactly-once semantics** — transaction boundaries are essential for idempotent, consistent processing
- **Denormalized views from normalized sources** — combining parent/child table events into a single document
- **Audit and compliance** — tracking complete transactions rather than isolated row changes

## Conclusion

Oracle GoldenGate excels at capturing changes, but its default formatters stop at the row level. For modern streaming architectures that demand transactional consistency, that's not enough. A lightweight custom formatter that emits transaction metadata at commit boundaries bridges this gap — turning a stream of disconnected row events into something a downstream consumer can reason about transactionally.

The implementation is minimal (under 100 lines of Java), the deployment is non-invasive, and the payoff is significant: your streaming pipeline can now process transactions, not just rows.
