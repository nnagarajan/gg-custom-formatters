package com.technext.goldengate.dasource.formatter;

import oracle.goldengate.datasource.DsConfiguration;
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
import java.util.function.Consumer;

/**
 * Formats transaction boundary (marker) events: per source transaction, its xid, csn, timestamp,
 * operation count, whether it is complete, and per table its operation count, key columns, and each
 * operation's key values and trail position. Inspired by Debezium transaction metadata:
 * https://debezium.io/documentation/reference/stable/connectors/oracle.html#oracle-transaction-metadata
 *
 * <h2>Contract: one marker per source transaction per delivery</h2>
 *
 * With {@code GROUPTRANSOPS} (default 1000), Replicat accumulates whole source transactions into
 * one delivered {@link DsTransaction}, whose own xid/csn are those of the first source transaction.
 * With {@code MAXTRANSOPS}, Replicat also splits a large source transaction across deliveries; each
 * delivery then holds a fragment of it. In {@code gg.handler.kafkahandler.mode=tx} the Kafka handler
 * calls {@link #formatOp} once per operation and sends one Kafka message per {@link DsTransaction}.
 *
 * <p>The formatter groups the delivered operations by their own source {@code xid:csn}
 * ({@link DsOperation#getXidStr()}, {@link DsOperation#getCsnStr()}), in trail order, and writes
 * one marker per group when it sees the delivery's last operation:
 * <ul>
 *   <li>One group: the message is a single JSON object.</li>
 *   <li>Several groups: the message is a JSON array of marker objects, in commit order, each with
 *       its own xid, csn and {@code tx_ts} (its first operation's timestamp).</li>
 * </ul>
 * A marker's {@code event_count} counts its source transaction's operations in this delivery.
 * {@code tx_complete} is false only for the delivery's last group when the delivery's last operation
 * does not end its source transaction ({@link DsOperation#isLast()}); the transaction then continues
 * in the next delivery under the same xid:csn.
 *
 * <p>Keys come from {@link KeyColumnResolver}. Any operation whose key cannot be produced makes
 * {@link #formatOp} throw before anything is written, so Replicat abends. The {@code markerKeys} and
 * {@code markerPos} properties turn the key fields and the {@code pos} field on independently; both
 * are off by default, so a marker carries only counts unless they are set to true.
 */
public class TransactionBoundaryEventJsonFormatter extends JsonFormatter {

    private static final Logger logger = LoggerFactory.getLogger(TransactionBoundaryEventJsonFormatter.class);

    private final KeyColumnResolver keys;
    private boolean includeKeys = false;
    private boolean includePos = false;
    private String keyColumnsFile;
    private boolean keyColumnsLoaded;

    public TransactionBoundaryEventJsonFormatter() {
        this(logger::warn);
    }

    /** For tests: warnings go to {@code warnings} instead of the log. */
    TransactionBoundaryEventJsonFormatter(Consumer<String> warnings) {
        this.keys = new KeyColumnResolver(warnings);
    }

    /**
     * Bound by GoldenGate from {@code gg.handler.<name>.format.keyColumnsFile}: a file of
     * {@code SCHEMA.TABLE=COL1,COL2} lines that overrides tables' primary keys. Only the path is kept
     * here, because properties may be set in any order; the file is read in {@link #init}, and only
     * when {@code markerKeys} is true.
     */
    public void setKeyColumnsFile(String path) {
        keyColumnsFile = path;
    }

    /**
     * Called by GoldenGate at startup, after all properties are set. Reads the key column file when
     * {@code markerKeys} is true; a missing or malformed file throws, so Replicat fails at startup.
     * With {@code markerKeys=false} the file is not read.
     */
    @Override
    public void init(DsConfiguration conf, DsMetaData meta) {
        super.init(conf, meta);
        loadKeyColumnsFile();
    }

    /** Reads the key column file once, if one is set and keys are on. */
    private void loadKeyColumnsFile() {
        if (keyColumnsLoaded || keyColumnsFile == null) {
            return;
        }
        keyColumnsLoaded = true;
        if (!includeKeys) {
            logger.info("markerKeys=false: key column file {} is not read", keyColumnsFile);
            return;
        }
        try {
            keys.loadMapping(keyColumnsFile);
            logger.info("Loaded key column file {}", keyColumnsFile);
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            throw e;
        }
    }

    /**
     * Bound from {@code gg.handler.<name>.format.markerKeys} ({@code false} by default). {@code false}
     * leaves {@code key_columns} and {@code key_ids} out of the markers and skips key resolution, so
     * tables need no primary key or key column mapping and missing or NULL key values do not abend.
     */
    public void setMarkerKeys(String value) {
        includeKeys = parseFlag("markerKeys", value);
    }

    /**
     * Bound from {@code gg.handler.<name>.format.markerPos} ({@code false} by default). {@code false}
     * leaves {@code pos} out of the markers.
     */
    public void setMarkerPos(String value) {
        includePos = parseFlag("markerPos", value);
    }

    private static boolean parseFlag(String name, String value) {
        String v = value == null ? "" : value.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        String message = "Invalid value for format." + name + ": '" + value + "' (expected true or false)";
        logger.error(message);
        throw new IllegalStateException(message);
    }

    /**
     * Called by the handler for each operation; writes the marker(s) once, on the delivery's last operation.
     * Throws {@link IllegalStateException}, before writing anything, when an operation's key cannot be
     * produced; Replicat then abends.
     */
    @Override
    public void formatOp(DsTransaction dsTransaction, DsOperation dsOperation, TableMetaData tableMetaData, NgFormattedData output) {
        if (dsTransaction.getLastOperation().getOperationSeqno() == dsOperation.getOperationSeqno()) {
            List<JsonObject> markers;
            try {
                loadKeyColumnsFile(); // in case init was not called
                markers = buildMarkers(dsTransaction);
            } catch (IllegalStateException e) {
                logger.error(e.getMessage());
                throw e;
            }
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

    /** Table metadata changed (for example after DDL): resolve and validate every table's key again. */
    @Override
    public void metaDataChanged(DsEvent e, DsMetaData meta) {
        logger.debug("metaDataChanged(): clearing resolved key columns");
        keys.clear();
    }

    /** One marker per source transaction in the delivered transaction, in trail order. */
    private List<JsonObject> buildMarkers(DsTransaction dsTransaction) {
        Map<String, SourceTx> groups = new LinkedHashMap<>();
        for (DsOperation op : dsTransaction.getOperations()) {
            String xid = op.getXidStr() != null ? op.getXidStr() : dsTransaction.getXidStr();
            String csn = op.getCsnStr() != null ? op.getCsnStr() : dsTransaction.getCsnStr();
            List<String> keyColumns = null;
            List<String> keyValues = null;
            if (includeKeys) {
                KeyColumnResolver.TableKey key = keys.resolve(op);
                keyColumns = key.names;
                keyValues = keys.values(op, key);
            } else {
                KeyColumnResolver.checkSupported(op);
            }
            SourceTx tx = groups.computeIfAbsent(xid + ":" + csn, k -> new SourceTx(xid, csn, op.getTimestamp()));
            tx.add(op.getTableName().getShortName(), keyColumns, keyValues, op.getPosition());
        }

        // MAXTRANSOPS splits only at delivery boundaries, so every group but the last ends in this
        // delivery. The last one is complete when the delivery's last operation ends its source transaction.
        boolean lastComplete = dsTransaction.getLastOperation().isLast();
        List<JsonObject> markers = new ArrayList<>(groups.size());
        if (groups.size() == 1) {
            // Single source transaction: keep the delivery-level values used before grouping support.
            SourceTx only = groups.values().iterator().next();
            markers.add(toJson(dsTransaction.getXidStr(), dsTransaction.getCsnStr(),
                    dsTransaction.getTransactionBeginTime(), lastComplete, only));
        } else {
            int i = 0;
            for (SourceTx tx : groups.values()) {
                boolean complete = ++i < groups.size() || lastComplete;
                markers.add(toJson(tx.xid, tx.csn, tx.timestamp, complete, tx));
            }
        }
        return markers;
    }

    private JsonObject toJson(String xid, String csn, DateString txTs, boolean complete, SourceTx tx) {
        JsonObjectBuilder jsonBuilder = this.getJsonProvider().createObjectBuilder();
        jsonBuilder.add(TransactionMetaDataType.XID.getValue(), xid);
        jsonBuilder.add(TransactionMetaDataType.CSN.getValue(), csn);
        jsonBuilder.add(TransactionMetaDataType.TX_TS.getValue(), txTs == null ? "" : txTs.toString());
        jsonBuilder.add(TransactionMetaDataType.EVENT_COUNT.getValue(), tx.total);
        jsonBuilder.add(TransactionMetaDataType.TX_COMPLETE.getValue(), complete);
        JsonArrayBuilder arrayBuilder = this.getJsonProvider().createArrayBuilder();
        for (Map.Entry<String, TableOps> e : tx.byTable.entrySet()) {
            TableOps t = e.getValue();
            JsonObjectBuilder opBuilder = this.getJsonProvider().createObjectBuilder();
            opBuilder.add(TransactionMetaDataType.DATA_COLLECTION.getValue(), e.getKey());
            opBuilder.add(TransactionMetaDataType.EVENT_COUNT.getValue(), t.positions.size());
            if (includeKeys) {
                opBuilder.add(TransactionMetaDataType.KEY_COLUMNS.getValue(), stringArray(t.keyColumns));
                JsonArrayBuilder keyIds = this.getJsonProvider().createArrayBuilder();
                for (List<String> keyValues : t.keyIds) {
                    keyIds.add(stringArray(keyValues));
                }
                opBuilder.add(TransactionMetaDataType.KEY_IDS.getValue(), keyIds);
            }
            if (includePos) {
                opBuilder.add(TransactionMetaDataType.POS.getValue(), stringArray(t.positions));
            }
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

    private JsonArrayBuilder stringArray(List<String> values) {
        JsonArrayBuilder array = this.getJsonProvider().createArrayBuilder();
        for (String v : values) {
            array.add(v == null ? "" : v);
        }
        return array;
    }

    /** Operations of one source transaction within the delivered transaction. */
    private static final class SourceTx {
        final String xid;
        final String csn;
        final DateString timestamp;
        final Map<String, TableOps> byTable = new LinkedHashMap<>();
        int total;

        SourceTx(String xid, String csn, DateString timestamp) {
            this.xid = xid;
            this.csn = csn;
            this.timestamp = timestamp;
        }

        void add(String table, List<String> keyColumns, List<String> keyValues, String position) {
            byTable.computeIfAbsent(table, k -> new TableOps(keyColumns)).add(keyValues, position);
            total++;
        }
    }

    /**
     * One table's operations within a source transaction, in trail order. Keys and positions are
     * appended together, so their lengths always equal the table's operation count. Positions are
     * always kept (they count the operations); keys are null entries when {@code markerKeys=false}.
     */
    private static final class TableOps {
        final List<String> keyColumns;
        final List<List<String>> keyIds = new ArrayList<>();
        final List<String> positions = new ArrayList<>();

        TableOps(List<String> keyColumns) {
            this.keyColumns = keyColumns;
        }

        void add(List<String> keyValues, String position) {
            keyIds.add(keyValues);
            positions.add(position);
        }
    }
}
