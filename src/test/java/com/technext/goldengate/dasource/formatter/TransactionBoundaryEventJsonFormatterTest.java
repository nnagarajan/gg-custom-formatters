package com.technext.goldengate.dasource.formatter;

import oracle.goldengate.datasource.DsOperation;
import oracle.goldengate.datasource.DsTransaction;
import oracle.goldengate.datasource.format.NgBAOSFormattedData;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.datasource.meta.TableName;
import oracle.goldengate.util.DateString;
import org.junit.Before;
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the marker contract the Spark pipeline depends on: exactly one marker per source
 * transaction (xid:csn), with per-table counts equal to that transaction's operations, including
 * when GROUPTRANSOPS delivers several source transactions as one DsTransaction.
 */
public class TransactionBoundaryEventJsonFormatterTest {

    private static final String ORDERS = "APPUSER.ORDERS";
    private static final String DETAILS = "APPUSER.ORDER_DETAILS";
    private static final String ITEMS = "APPUSER.ORDER_LINE_ITEMS";

    private TransactionBoundaryEventJsonFormatter formatter;
    private int seqno;

    @Before
    public void setUp() {
        formatter = new TransactionBoundaryEventJsonFormatter();
        seqno = 0;
    }

    // ---- single source transaction: unchanged output ----

    @Test
    public void singleSourceTransactionIsOneJsonObject() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("TEST-XID-001", "123456", "SCHEMA1.TABLE1"));
        ops.add(op("TEST-XID-001", "123456", "SCHEMA1.TABLE1"));
        DsTransaction tx = delivered("TEST-XID-001", "123456", ops);

        JsonStructure out = format(tx);

        assertEquals(JsonValue.ValueType.OBJECT, out.getValueType());
        JsonObject json = (JsonObject) out;
        assertEquals("TEST-XID-001", json.getString("xid"));
        assertEquals("123456", json.getString("csn"));
        assertEquals("2023-12-01 10:00:00.000", json.getString("tx_ts"));
        assertEquals(2, json.getInt("event_count"));
        assertEquals(1, json.getJsonArray("data_collections").size());
        JsonObject dc = json.getJsonArray("data_collections").getJsonObject(0);
        assertEquals("TABLE1", dc.getString("data_collection"));
        assertEquals(2, dc.getInt("event_count"));
    }

    @Test
    public void singleTransactionCountsEachTable() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("X1", "500", ORDERS));
        ops.add(op("X1", "500", DETAILS));
        ops.add(op("X1", "500", ITEMS));
        ops.add(op("X1", "500", ITEMS));
        ops.add(op("X1", "500", ITEMS));

        JsonObject json = (JsonObject) format(delivered("X1", "500", ops));

        assertEquals(5, json.getInt("event_count"));
        Map<String, Integer> counts = counts(json);
        assertEquals(Integer.valueOf(1), counts.get("ORDERS"));
        assertEquals(Integer.valueOf(1), counts.get("ORDER_DETAILS"));
        assertEquals(Integer.valueOf(3), counts.get("ORDER_LINE_ITEMS"));
        // data_collections are listed in first-appearance (trail) order
        List<String> order = new ArrayList<>();
        for (JsonValue v : json.getJsonArray("data_collections")) {
            order.add(((JsonObject) v).getString("data_collection"));
        }
        assertEquals(Arrays.asList("ORDERS", "ORDER_DETAILS", "ORDER_LINE_ITEMS"), order);
    }

    // ---- GROUPTRANSOPS: several source transactions in one delivery ----

    @Test
    public void groupedSourceTransactionsProduceOneMarkerEach() {
        // As in the GROUPTRANSOPS documentation: three whole source transactions grouped into
        // one Replicat transaction (scaled down). The DsTransaction reports the first one's ids.
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("X1", "500", ORDERS));
        ops.add(op("X1", "500", ITEMS));
        ops.add(op("X1", "500", ITEMS));
        ops.add(op("X2", "600", ORDERS));
        ops.add(op("X2", "600", DETAILS));
        ops.add(op("X3", "700", ITEMS));
        ops.add(op("X3", "700", ITEMS));
        ops.add(op("X3", "700", ITEMS));
        DsTransaction tx = delivered("X1", "500", ops);

        JsonStructure out = format(tx);

        assertEquals(JsonValue.ValueType.ARRAY, out.getValueType());
        JsonArray markers = (JsonArray) out;
        assertEquals(3, markers.size());

        assertMarker(markers.getJsonObject(0), "X1", "500", 3, map("ORDERS", 1, "ORDER_LINE_ITEMS", 2));
        assertMarker(markers.getJsonObject(1), "X2", "600", 2, map("ORDERS", 1, "ORDER_DETAILS", 1));
        assertMarker(markers.getJsonObject(2), "X3", "700", 3, map("ORDER_LINE_ITEMS", 3));
    }

    @Test
    public void groupedMarkersCarryEachTransactionsOwnTimestamp() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("X1", "500", ORDERS, "2026-11-07 21:24:36.000000"));
        ops.add(op("X2", "600", ORDERS, "2026-11-07 21:24:37.000000"));

        JsonArray markers = (JsonArray) format(delivered("X1", "500", ops));

        assertEquals("2026-11-07 21:24:36.000000", markers.getJsonObject(0).getString("tx_ts"));
        assertEquals("2026-11-07 21:24:37.000000", markers.getJsonObject(1).getString("tx_ts"));
    }

    @Test
    public void sameCsnDifferentXidAreSeparateMarkers() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("A", "500", ORDERS));
        ops.add(op("B", "500", ORDERS));
        ops.add(op("B", "500", ITEMS));

        JsonArray markers = (JsonArray) format(delivered("A", "500", ops));

        assertEquals(2, markers.size());
        assertMarker(markers.getJsonObject(0), "A", "500", 1, map("ORDERS", 1));
        assertMarker(markers.getJsonObject(1), "B", "500", 2, map("ORDERS", 1, "ORDER_LINE_ITEMS", 1));
    }

    @Test
    public void markerCountsAddUpToTheDeliveredOperations() {
        List<DsOperation> ops = new ArrayList<>();
        String[] tables = {ORDERS, DETAILS, ITEMS, ITEMS};
        for (int t = 0; t < 50; t++) {
            for (int i = 0; i <= t % 4; i++) {
                ops.add(op("X" + t, String.valueOf(1000 + t), tables[i]));
            }
        }

        JsonArray markers = (JsonArray) format(delivered("X0", "1000", ops));

        assertEquals(50, markers.size());
        int total = 0;
        for (int t = 0; t < 50; t++) {
            JsonObject m = markers.getJsonObject(t);
            assertEquals("X" + t, m.getString("xid"));
            assertEquals(String.valueOf(1000 + t), m.getString("csn"));
            int perTable = 0;
            for (Integer c : counts(m).values()) perTable += c;
            assertEquals(m.getInt("event_count"), perTable);
            assertEquals(t % 4 + 1, m.getInt("event_count"));
            total += m.getInt("event_count");
        }
        assertEquals(ops.size(), total);
    }

    @Test
    public void operationWithoutOwnIdsFallsBackToTheDeliveredTransaction() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op(null, null, ORDERS));
        ops.add(op(null, null, ITEMS));

        JsonObject json = (JsonObject) format(delivered("X1", "500", ops));

        assertMarker(json, "X1", "500", 2, map("ORDERS", 1, "ORDER_LINE_ITEMS", 1));
    }

    @Test
    public void onlyTheLastOperationWritesOutput() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("X1", "500", ORDERS));
        ops.add(op("X1", "500", ITEMS));
        DsTransaction tx = delivered("X1", "500", ops);

        NgBAOSFormattedData output = new NgBAOSFormattedData();
        formatter.formatOp(tx, ops.get(0), mock(TableMetaData.class), output);

        assertEquals(0, output.getByteArrayOutputStream(0).size());
    }

    // ---- helpers ----

    private DsOperation op(String xid, String csn, String table) {
        return op(xid, csn, table, "2026-11-07 21:24:36.000000");
    }

    private DsOperation op(String xid, String csn, String table, String ts) {
        DsOperation op = mock(DsOperation.class);
        when(op.getXidStr()).thenReturn(xid);
        when(op.getCsnStr()).thenReturn(csn);
        when(op.getTableName()).thenReturn(new TableName(table));
        when(op.getOperationSeqno()).thenReturn(++seqno);
        when(op.getTimestamp()).thenReturn(new DateString(ts));
        return op;
    }

    private DsTransaction delivered(String xid, String csn, List<DsOperation> ops) {
        DsTransaction tx = mock(DsTransaction.class);
        when(tx.getXidStr()).thenReturn(xid);
        when(tx.getCsnStr()).thenReturn(csn);
        when(tx.getTransactionBeginTime()).thenReturn(new DateString("2023-12-01 10:00:00.000"));
        when(tx.getTotalOps()).thenReturn(ops.size());
        when(tx.getOperations()).thenReturn(ops);
        when(tx.getLastOperation()).thenReturn(ops.get(ops.size() - 1));
        return tx;
    }

    /** Runs the formatter as the Kafka handler does in mode=tx and parses the message. */
    private JsonStructure format(DsTransaction tx) {
        NgBAOSFormattedData output = new NgBAOSFormattedData();
        for (DsOperation op : tx.getOperations()) {
            formatter.formatOp(tx, op, mock(TableMetaData.class), output);
        }
        String message = new String(output.getByteArrayOutputStream(0).toByteArray(), StandardCharsets.UTF_8);
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            return reader.read();
        }
    }

    private static Map<String, Integer> counts(JsonObject marker) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonValue v : marker.getJsonArray("data_collections")) {
            JsonObject dc = (JsonObject) v;
            counts.put(dc.getString("data_collection"), dc.getInt("event_count"));
        }
        return counts;
    }

    private static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static void assertMarker(JsonObject m, String xid, String csn, int total, Map<String, Integer> perTable) {
        assertEquals(xid, m.getString("xid"));
        assertEquals(csn, m.getString("csn"));
        assertEquals(total, m.getInt("event_count"));
        assertEquals(perTable, counts(m));
        assertTrue(m.containsKey("tx_ts"));
        // marker shape is unchanged: exactly these fields, no more, no fewer
        assertEquals(new HashSet<>(Arrays.asList("xid", "csn", "tx_ts", "event_count", "data_collections")), m.keySet());
        for (JsonValue v : m.getJsonArray("data_collections")) {
            assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count")), ((JsonObject) v).keySet());
        }
    }
}
