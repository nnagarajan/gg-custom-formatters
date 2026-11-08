package com.technext.goldengate.dasource.formatter;

import oracle.goldengate.datasource.DsEvent;
import oracle.goldengate.datasource.DsOperation;
import oracle.goldengate.datasource.DsTransaction;
import oracle.goldengate.datasource.format.NgBAOSFormattedData;
import oracle.goldengate.datasource.meta.DsMetaData;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.format.NgFormattedData;
import oracle.goldengate.format.json.JsonFormatter;
import oracle.goldengate.util.DateString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonStructure;
import javax.json.JsonWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formats transaction boundary (marker) events: per source transaction, its xid, csn, timestamp,
 * total operation count and operation count per table. Inspired by Debezium transaction metadata:
 * https://debezium.io/documentation/reference/stable/connectors/oracle.html#oracle-transaction-metadata
 *
 * <h2>Contract: exactly one marker per source transaction</h2>
 *
 * With {@code GROUPTRANSOPS} (default 1000), Replicat accumulates whole source transactions into
 * one delivered {@link DsTransaction}, whose own xid/csn are those of the first source transaction.
 * GoldenGate never splits a source transaction across deliveries (unless {@code MAXTRANSOPS} is
 * set, which is not supported). In {@code gg.handler.kafkahandler.mode=tx} the Kafka handler calls
 * {@link #formatOp} once per operation and sends one Kafka message per {@link DsTransaction}.
 *
 * <p>The formatter groups the delivered operations by their own source {@code xid:csn}
 * ({@link DsOperation#getXidStr()}, {@link DsOperation#getCsnStr()}), in trail order, and writes
 * one marker per group when it sees the delivery's last operation:
 * <ul>
 *   <li>One source transaction: the message is a single JSON object (as before).</li>
 *   <li>Several source transactions: the message is a JSON array of marker objects, in commit
 *       order, each with its own xid, csn and {@code tx_ts} (its first operation's timestamp).</li>
 * </ul>
 */
public class TransactionBoundaryEventJsonFormatter extends JsonFormatter {

    private static final Logger logger = LoggerFactory.getLogger(TransactionBoundaryEventJsonFormatter.class);

    /**
     * Called by the handler for each operation; writes the marker(s) once, on the delivery's last operation.
     */
    @Override
    public void formatOp(DsTransaction dsTransaction, DsOperation dsOperation, TableMetaData tableMetaData, NgFormattedData output) {
        if (dsTransaction.getLastOperation().getOperationSeqno() == dsOperation.getOperationSeqno()) {
            List<JsonObject> markers = buildMarkers(dsTransaction);
            logger.debug("Formatting {} transaction boundary event(s) for transaction: {}", markers.size(), dsTransaction.getXidStr());
            writeJson(markers, (NgBAOSFormattedData) output);
        }
    }

    /**
     * No-op. The Kafka handler never calls {@code endTx} in {@code mode=tx}; output is written in
     * {@link #formatOp} on the delivery's last operation instead.
     */
    @Override
    public void endTx(DsTransaction dsTransaction, DsMetaData dsMetaData, NgFormattedData output) {
        logger.debug("endTx() not implemented");
    }

    @Override
    public void metaDataChanged(DsEvent e, DsMetaData meta) {
        logger.debug("metaDataChanged() not implemented");
    }

    /** One marker per source transaction in the delivered transaction, in trail order. */
    private List<JsonObject> buildMarkers(DsTransaction dsTransaction) {
        Map<String, SourceTx> groups = new LinkedHashMap<>();
        for (DsOperation op : dsTransaction.getOperations()) {
            String xid = op.getXidStr() != null ? op.getXidStr() : dsTransaction.getXidStr();
            String csn = op.getCsnStr() != null ? op.getCsnStr() : dsTransaction.getCsnStr();
            SourceTx tx = groups.computeIfAbsent(xid + ":" + csn, k -> new SourceTx(xid, csn, op.getTimestamp()));
            tx.add(op.getTableName().getShortName());
        }

        List<JsonObject> markers = new ArrayList<>(groups.size());
        if (groups.size() == 1) {
            // Single source transaction: keep the delivery-level values used before grouping support.
            SourceTx only = groups.values().iterator().next();
            markers.add(toJson(dsTransaction.getXidStr(), dsTransaction.getCsnStr(),
                    dsTransaction.getTransactionBeginTime(), only));
        } else {
            for (SourceTx tx : groups.values()) {
                markers.add(toJson(tx.xid, tx.csn, tx.timestamp, tx));
            }
        }
        return markers;
    }

    private JsonObject toJson(String xid, String csn, DateString txTs, SourceTx tx) {
        JsonObjectBuilder jsonBuilder = this.getJsonProvider().createObjectBuilder();
        jsonBuilder.add(TransactionMetaDataType.XID.getValue(), xid);
        jsonBuilder.add(TransactionMetaDataType.CSN.getValue(), csn);
        jsonBuilder.add(TransactionMetaDataType.TX_TS.getValue(), txTs == null ? "" : txTs.toString());
        jsonBuilder.add(TransactionMetaDataType.EVENT_COUNT.getValue(), tx.total);
        JsonArrayBuilder arrayBuilder = this.getJsonProvider().createArrayBuilder();
        for (Map.Entry<String, Long> e : tx.countByTable.entrySet()) {
            JsonObjectBuilder opBuilder = this.getJsonProvider().createObjectBuilder();
            opBuilder.add(TransactionMetaDataType.DATA_COLLECTION.getValue(), e.getKey());
            opBuilder.add(TransactionMetaDataType.EVENT_COUNT.getValue(), e.getValue());
            arrayBuilder.add(opBuilder);
        }
        jsonBuilder.add(TransactionMetaDataType.DATA_COLLECTIONS.getValue(), arrayBuilder.build());
        return jsonBuilder.build();
    }

    /** One marker: a JSON object. Several: a JSON array of marker objects. */
    private void writeJson(List<JsonObject> markers, NgBAOSFormattedData formattedData) {
        JsonStructure json;
        if (markers.size() == 1) {
            json = markers.get(0);
        } else {
            JsonArrayBuilder array = this.getJsonProvider().createArrayBuilder();
            markers.forEach(array::add);
            json = array.build();
        }
        JsonWriter jsonWriter;
        if (this.config == null || this.config.getCharset() == null) {
            jsonWriter = this.getJsonWriterFactory().createWriter(formattedData.getByteArrayOutputStream(0));
        } else {
            jsonWriter = this.getJsonWriterFactory().createWriter(formattedData.getByteArrayOutputStream(0), this.config.getCharset());
        }
        jsonWriter.write(json);
    }

    /** Operations of one source transaction within the delivered transaction. */
    private static final class SourceTx {
        final String xid;
        final String csn;
        final DateString timestamp;
        final Map<String, Long> countByTable = new LinkedHashMap<>();
        int total;

        SourceTx(String xid, String csn, DateString timestamp) {
            this.xid = xid;
            this.csn = csn;
            this.timestamp = timestamp;
        }

        void add(String table) {
            countByTable.merge(table, 1L, Long::sum);
            total++;
        }
    }
}
