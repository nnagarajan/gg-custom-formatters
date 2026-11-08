# Design

## Context

See proposal.md (Why) for the failure. Facts that shape the approach, verified against the GoldenGate for Big Data 21.4 jars and rep3 logs:

- `gg.handler.kafkahandler.mode=tx`: `KafkaHandler.transactionCommit` calls `formatter.formatOp(...)` once per operation into one `NgBAOSFormattedData`, then sends **one** Kafka message per `DsTransaction`. It never calls `beginTx`/`endTx`, which is why the formatter writes only on the last operation.
- `DsTransaction.getXidStr()`/`getCsnStr()` are fixed at delivery start, so in a grouped delivery they belong to the first source transaction.
- Every `DsOperation` carries its own `getXidStr()`/`getCsnStr()`, plus `getTimestamp()`.
- GoldenGate never splits a source transaction across deliveries unless `MAXTRANSOPS` is set.

The current formatter (`TransactionBoundaryEventJsonFormatter.formatTransaction`) uses the delivery's ids, `getTotalOps()`, and a `HashMap` of counts over all operations, and writes with `jsonWriter.writeObject(...)`. A reference implementation and tests already pass in `transactional-cdc-processing-spark/gg-formatter`; this change brings this repo to that behavior.

## Goals / Non-Goals

**Goals:**
- Correct markers for grouped deliveries without changing the single-transaction message.
- No new dependencies; Java 8 source level; same build and artifact name.

**Non-Goals:**
- Handling deliveries that contain a *partial* source transaction (`MAXTRANSOPS`).
- Emitting one Kafka record per marker.

## Decisions

1. **Group by the operation's own `xid:csn`, in a `LinkedHashMap`.** Key is `xid + ":" + csn`, so equal CSNs with different xids stay separate, and insertion order preserves trail/commit order. Null operation ids fall back to the `DsTransaction`'s value so an operation is never dropped or keyed under `"null"`.
   *Alternative:* detect boundaries via `DsOperation.getTxState()` — rejected, its semantics in grouped deliveries are unverified.

2. **Small private accumulator per source transaction** (xid, csn, first-op timestamp, `LinkedHashMap<String, Long>` table counts, total). `event_count` is the accumulator's total, not `DsTransaction.getTotalOps()`, which covers the whole delivery.

3. **Single group keeps today's values.** With one group the marker uses `DsTransaction.getXidStr()`/`getCsnStr()`/`getTransactionBeginTime()`, so existing single-transaction messages are unchanged in field values. With several groups each marker uses its own ids and its first operation's `getTimestamp()` as `tx_ts` (there is no per-source-transaction begin time on `DsOperation`). A null timestamp is written as `""` rather than failing.

4. **Object vs array.** One marker → JSON object (backward compatible); several → JSON array. Written with `jsonWriter.write(JsonStructure)` through the existing `getJsonWriterFactory()` and charset branch. `this.config == null` is treated as "no charset", which also lets tests run without a configured formatter.
   *Alternative:* always an array — rejected, it would change every existing message; the consumer already accepts both.
   *Alternative:* newline-delimited objects — rejected, not a single JSON value and the consumer does not parse it.

5. **`data_collections` order becomes first-appearance order** (was `HashMap` order). Field values are identical; only entry order may differ. The consumer matches by `data_collection` name, so this is not a compatibility issue, and the order is now deterministic.

6. **`endTx`/`metaDataChanged` stay no-ops**, with a comment that the Kafka handler never calls `endTx` in tx mode.

## Risks / Trade-offs

- [Consumer that only accepts a JSON object breaks on grouped deliveries] → The only consumer (`CdcJsonParsing.parseTransactionMetadata`) parses both forms; documented in README.
- [Large grouped message] → ~180–280 B per marker, ≤ ~GROUPTRANSOPS markers (~276 KB at 1000) vs ~1 MB default `max.message.bytes`; gzip enabled. Raising GROUPTRANSOPS far above 1000 would need a matching broker/producer limit.
- [`MAXTRANSOPS` set later produces partial markers that never match] → README rule: must stay unset in rep3.
- [Grouped `tx_ts` is first-operation time, not transaction begin time] → Accepted; downstream uses `tx_ts` only informationally.

## Migration Plan

1. `mvn package` (needs GG jars at `/opt/oracle/ggbd/ggjava/resources/lib`) → `target/gg-custom-formatters-1.0-SNAPSHOT.jar`.
2. Copy to `/opt/oracle/ggbd/ggjava/resources/customlib/`, restart replicat rep3 (manual, not part of this change). Order relative to the Spark pipeline does not matter.
3. Rollback: restore the previous jar and restart rep3.
