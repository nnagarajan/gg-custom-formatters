package com.technext.goldengate.dasource.formatter;

import oracle.goldengate.datasource.DsColumn;
import oracle.goldengate.datasource.DsOperation;
import oracle.goldengate.datasource.DsOperation.OpType;
import oracle.goldengate.datasource.DsTransaction;
import oracle.goldengate.datasource.format.NgBAOSFormattedData;
import oracle.goldengate.datasource.meta.ColumnMetaData;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.datasource.meta.TableName;
import oracle.goldengate.util.DateString;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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
        formatter = keysAndPos(new TransactionBoundaryEventJsonFormatter());
        seqno = 0;
    }

    /** Most tests check keys and positions, which are off by default. */
    private static TransactionBoundaryEventJsonFormatter keysAndPos(TransactionBoundaryEventJsonFormatter f) {
        f.setMarkerKeys("true");
        f.setMarkerPos("true");
        return f;
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
        assertTrue(json.getBoolean("tx_complete"));
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

    // ---- key_columns, key_ids, pos ----

    private static final String[] ORDER_COLS = {"ORDER_ID", "STATUS"};
    private static final String[] ITEM_COLS = {"LINE_ITEM_ID", "ORDER_ID", "LINE_NO", "QTY"};

    @Test
    public void primaryKeyFromMetadata() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta(ORDER_COLS, "ORDER_ID"), insert("101"), insert("NEW")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("['ORDER_ID']"), dc.getJsonArray("key_columns"));
    }

    @Test
    public void compositeKey() {
        TableMetaData meta = meta(new String[]{"ORDER_ID", "LINE_NO", "QTY"}, "ORDER_ID", "LINE_NO");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta, insert("101"), insert("A"), insert("1")));
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta, insert("102"), insert("B"), insert("2")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDER_LINE_ITEMS");

        assertEquals(json("['ORDER_ID','LINE_NO']"), dc.getJsonArray("key_columns"));
        assertEquals(json("[['101','A'],['102','B']]"), dc.getJsonArray("key_ids"));
    }

    @Test
    public void singleColumnKeyIsWrapped() {
        TableMetaData meta = meta(ORDER_COLS, "ORDER_ID");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta, insert("101"), insert("NEW")));
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta, insert("102"), insert("NEW")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("[['101'],['102']]"), dc.getJsonArray("key_ids"));
    }

    @Test
    public void updateWithoutTheKeyInTheAfterImage() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_UPDATE_FIELDCOMP, meta(ORDER_COLS, "ORDER_ID"),
                beforeOnly("101"), both("NEW", "SHIPPED")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("[['101']]"), dc.getJsonArray("key_ids"));
    }

    @Test
    public void updatePrefersTheAfterImage() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_UPDATE, meta(ORDER_COLS, "ORDER_ID"),
                both("101", "105"), both("NEW", "SHIPPED")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("[['105']]"), dc.getJsonArray("key_ids"));
    }

    @Test
    public void deleteUsesTheBeforeImage() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_DELETE, meta(ORDER_COLS, "ORDER_ID"),
                both("101", "999"), beforeOnly("NEW")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("[['101']]"), dc.getJsonArray("key_ids"));
    }

    @Test
    public void sameRowChangedTwiceIsListedOncePerOperation() {
        TableMetaData meta = meta(ORDER_COLS, "ORDER_ID");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_UPDATE, meta, both("101", "101"), both("NEW", "PAID")));
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_UPDATE, meta, both("101", "101"), both("PAID", "SHIPPED")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("[['101'],['101']]"), dc.getJsonArray("key_ids"));
        assertEquals(2, dc.getJsonArray("pos").size());
    }

    @Test
    public void positionsAlignWithKeys() {
        TableMetaData meta = meta(ORDER_COLS, "ORDER_ID");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta, insert("101"), insert("NEW")));
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta, insert("102"), insert("NEW")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("[['101'],['102']]"), dc.getJsonArray("key_ids"));
        assertEquals(json("['00000000010000004321','00000000010000004587']"), dc.getJsonArray("pos"));
    }

    @Test
    public void positionsAndKeysFollowTrailOrderPerTable() {
        TableMetaData orders = meta(ORDER_COLS, "ORDER_ID");
        TableMetaData items = meta(ITEM_COLS, "LINE_ITEM_ID");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, orders, insert("101"), insert("NEW")));
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, items, insert("9001"), insert("101"), insert("A"), insert("1")));
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, orders, insert("102"), insert("NEW")));

        JsonObject m = (JsonObject) format(delivered("X1", "500", ops));

        assertEquals(json("[['101'],['102']]"), collection(m, "ORDERS").getJsonArray("key_ids"));
        assertEquals(json("['" + pos(1) + "','" + pos(3) + "']"), collection(m, "ORDERS").getJsonArray("pos"));
        assertEquals(json("[['9001']]"), collection(m, "ORDER_LINE_ITEMS").getJsonArray("key_ids"));
        assertEquals(json("['" + pos(2) + "']"), collection(m, "ORDER_LINE_ITEMS").getJsonArray("pos"));
    }

    @Test
    public void arrayLengthsEqualTheCount() {
        TableMetaData meta = meta(ORDER_COLS, "ORDER_ID");
        List<DsOperation> ops = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta, insert(String.valueOf(100 + i)), insert("NEW")));
        }

        JsonObject m = (JsonObject) format(delivered("X1", "500", ops));

        JsonObject dc = collection(m, "ORDERS");
        assertEquals(50, dc.getInt("event_count"));
        assertEquals(50, dc.getJsonArray("key_ids").size());
        assertEquals(50, dc.getJsonArray("pos").size());
        assertShape(m);
    }

    // ---- key column file and abends ----

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void keyColumnFileOverridesThePrimaryKey() throws IOException {
        formatter.setKeyColumnsFile(keyFile("APPUSER.ORDER_LINE_ITEMS=ORDER_ID,LINE_NO"));
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta(ITEM_COLS, "LINE_ITEM_ID"),
                insert("9001"), insert("101"), insert("A"), insert("1")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDER_LINE_ITEMS");

        assertEquals(json("['ORDER_ID','LINE_NO']"), dc.getJsonArray("key_columns"));
        assertEquals(json("[['101','A']]"), dc.getJsonArray("key_ids"));
    }

    @Test
    public void keyColumnFileMatchesTablesCaseInsensitively() throws IOException {
        formatter.setKeyColumnsFile(keyFile("# comment", "", "appuser.order_line_items = order_id , line_no"));
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", "APPUSER.ORDER_LINE_ITEMS", OpType.DO_INSERT, meta(ITEM_COLS, "LINE_ITEM_ID"),
                insert("9001"), insert("101"), insert("A"), insert("1")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDER_LINE_ITEMS");

        assertEquals(json("['ORDER_ID','LINE_NO']"), dc.getJsonArray("key_columns"));
    }

    @Test
    public void keyColumnFileThatDoesNotExistFailsAtStartup() {
        String path = tmp.getRoot().getAbsolutePath() + "/nope.properties";

        formatter.setKeyColumnsFile(path);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> formatter.init(null, null));

        assertTrue(e.getMessage(), e.getMessage().contains(path));
    }

    @Test
    public void malformedKeyColumnFileFailsAtStartupNamingTheLine() throws IOException {
        String path = keyFile("APPUSER.ORDERS=ORDER_ID", "APPUSER.ORDER_LINE_ITEMS=ORDER_ID,,LINE_NO");

        formatter.setKeyColumnsFile(path);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> formatter.init(null, null));

        assertTrue(e.getMessage(), e.getMessage().contains(path));
        assertTrue(e.getMessage(), e.getMessage().contains("line 2"));
    }

    @Test
    public void keyColumnFileLineWithoutColumnsFailsAtStartup() throws IOException {
        String path = keyFile("APPUSER.ORDERS=");

        formatter.setKeyColumnsFile(path);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> formatter.init(null, null));

        assertTrue(e.getMessage(), e.getMessage().contains("line 1"));
    }

    @Test
    public void missingKeyValueAbends() throws IOException {
        formatter.setKeyColumnsFile(keyFile("APPUSER.ORDER_LINE_ITEMS=ORDER_ID,LINE_NO"));
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_UPDATE_FIELDCOMP, meta(ITEM_COLS, "LINE_ITEM_ID"),
                beforeOnly("9001"), beforeOnly("101"), missing(), both("1", "2")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> format(delivered("X1", "500", ops)));

        assertMessage(e, "LINE_NO", "MISSING", "APPUSER.ORDER_LINE_ITEMS", "DO_UPDATE_FIELDCOMP", pos(1), "xid=X1", "csn=500");
    }

    @Test
    public void nullKeyValueAbends() throws IOException {
        formatter.setKeyColumnsFile(keyFile("APPUSER.ORDER_LINE_ITEMS=ORDER_ID,LINE_NO"));
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta(ITEM_COLS, "LINE_ITEM_ID"),
                insert("9001"), insert("101"), insert(null), insert("1")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> format(delivered("X1", "500", ops)));

        assertMessage(e, "LINE_NO", "NULL", "APPUSER.ORDER_LINE_ITEMS", "DO_INSERT", pos(1));
    }

    @Test
    public void truncateAbends() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_TRUNCATE, meta(ORDER_COLS, "ORDER_ID")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> format(delivered("X1", "500", ops)));

        assertMessage(e, "APPUSER.ORDERS", "DO_TRUNCATE", pos(1), "truncates must not be captured");
    }

    @Test
    public void mappedColumnThatDoesNotExistAbends() throws IOException {
        formatter.setKeyColumnsFile(keyFile("APPUSER.ORDER_LINE_ITEMS=ORDER_ID,LINE_NUM"));
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta(ITEM_COLS, "LINE_ITEM_ID"),
                insert("9001"), insert("101"), insert("A"), insert("1")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> format(delivered("X1", "500", ops)));

        assertMessage(e, "APPUSER.ORDER_LINE_ITEMS", "LINE_NUM");
    }

    @Test
    public void tableWithoutPrimaryKeyOrMappingAbends() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta(ORDER_COLS), insert("101"), insert("NEW")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> format(delivered("X1", "500", ops)));

        assertMessage(e, "APPUSER.ORDERS", "no primary key", "key column");
    }

    @Test
    public void nullableMappedColumnLogsAWarningAndContinues() throws IOException {
        List<String> warnings = new ArrayList<>();
        formatter = keysAndPos(new TransactionBoundaryEventJsonFormatter(warnings::add));
        formatter.setKeyColumnsFile(keyFile("APPUSER.ORDER_LINE_ITEMS=ORDER_ID,LINE_NO"));
        TableMetaData meta = meta(ITEM_COLS, new HashSet<>(Arrays.asList("LINE_NO")), "LINE_ITEM_ID");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta, insert("9001"), insert("101"), insert("A"), insert("1")));
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta, insert("9002"), insert("101"), insert("B"), insert("1")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDER_LINE_ITEMS");

        assertEquals(json("[['101','A'],['101','B']]"), dc.getJsonArray("key_ids"));
        assertEquals("warned once, on first sight of the table", 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("APPUSER.ORDER_LINE_ITEMS") && warnings.get(0).contains("LINE_NO"));
    }

    @Test
    public void failingDeliveryWritesNothing() {
        TableMetaData meta = meta(ORDER_COLS, "ORDER_ID");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta, insert("101"), insert("NEW")));
        ops.add(keyedOp("X2", "600", ORDERS, OpType.DO_INSERT, meta, insert(null), insert("NEW")));
        DsTransaction tx = delivered("X1", "500", ops);
        NgBAOSFormattedData output = new NgBAOSFormattedData();

        formatter.formatOp(tx, ops.get(0), mock(TableMetaData.class), output);
        assertThrows(IllegalStateException.class, () -> formatter.formatOp(tx, ops.get(1), mock(TableMetaData.class), output));

        assertEquals(0, output.getByteArrayOutputStream(0).size());
    }

    @Test
    public void settingThePathDoesNotReadTheFile() {
        formatter.setKeyColumnsFile(tmp.getRoot().getAbsolutePath() + "/nope.properties");
    }

    @Test
    public void keysOffDoesNotReadTheKeyColumnFile() {
        // path before flag: GoldenGate may set properties in any order
        formatter.setKeyColumnsFile(tmp.getRoot().getAbsolutePath() + "/nope.properties");
        formatter.setMarkerKeys("false");
        formatter.init(null, null);

        JsonObject dc = collection(twoOrders(), "ORDERS");

        assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count", "pos")), dc.keySet());
    }

    @Test
    public void keysOffDoesNotReadTheKeyColumnFileFlagFirst() {
        formatter.setMarkerKeys("false");
        formatter.setKeyColumnsFile(tmp.getRoot().getAbsolutePath() + "/nope.properties");
        formatter.init(null, null);

        assertEquals(2, collection(twoOrders(), "ORDERS").getInt("event_count"));
    }

    @Test
    public void keyColumnFileIsReadOnFirstDeliveryWhenInitWasNotCalled() throws IOException {
        String missing = tmp.getRoot().getAbsolutePath() + "/nope.properties";
        formatter.setKeyColumnsFile(missing);
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("X1", "500", ORDERS));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> format(delivered("X1", "500", ops)));

        assertMessage(e, "Cannot read key column file", missing);
    }

    @Test
    public void readmeKeyColumnFileExampleLoads() throws IOException {
        String readme = new String(Files.readAllBytes(new File("README.md").toPath()), StandardCharsets.UTF_8);
        int start = readme.indexOf("# dirprm/key_columns.properties");
        String block = readme.substring(start, readme.indexOf("```", start));
        formatter.setKeyColumnsFile(keyFile(block.split("\n")));
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta(ITEM_COLS, "LINE_ITEM_ID"),
                insert("9001"), insert("101"), insert("A"), insert("1")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDER_LINE_ITEMS");

        assertEquals(json("['ORDER_ID','LINE_NO']"), dc.getJsonArray("key_columns"));
    }

    @Test
    public void sampleKeyColumnFileLoads() {
        formatter.setKeyColumnsFile("src/main/resources/key_columns.properties");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta(ITEM_COLS, "LINE_ITEM_ID"),
                insert("9001"), insert("101"), insert("A"), insert("1")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDER_LINE_ITEMS");

        assertEquals(json("['ORDER_ID','LINE_NO']"), dc.getJsonArray("key_columns"));
    }

    private String keyFile(String... lines) throws IOException {
        File f = tmp.newFile();
        Files.write(f.toPath(), Arrays.asList(lines), StandardCharsets.UTF_8);
        return f.getAbsolutePath();
    }

    private static void assertMessage(Exception e, String... parts) {
        for (String part : parts) {
            assertTrue("'" + part + "' not in: " + e.getMessage(), e.getMessage().contains(part));
        }
    }

    // ---- tx_complete and MAXTRANSOPS fragments ----

    @Test
    public void wholeTransactionInOneDeliveryIsComplete() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("X2", "600", ORDERS));
        ops.add(op("X2", "600", ITEMS));

        JsonObject m = (JsonObject) format(delivered("X2", "600", ops));

        assertTrue(m.getBoolean("tx_complete"));
        assertShape(m);
    }

    @Test
    public void sourceTransactionSplitAcrossDeliveries() {
        // MAXTRANSOPS 2: X1's three operations arrive as two deliveries
        TableMetaData meta = meta(ORDER_COLS, "ORDER_ID");
        List<DsOperation> first = new ArrayList<>();
        first.add(notLast(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta, insert("101"), insert("NEW"))));
        first.add(notLast(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta, insert("102"), insert("NEW"))));
        List<DsOperation> second = new ArrayList<>();
        second.add(keyedOp("X1", "500", ITEMS, OpType.DO_INSERT, meta, insert("103"), insert("NEW")));

        JsonObject m1 = (JsonObject) format(delivered("X1", "500", first));
        JsonObject m2 = (JsonObject) format(delivered("X1", "500", second));

        assertMarker(m1, "X1", "500", 2, map("ORDER_LINE_ITEMS", 2));
        assertFalse(m1.getBoolean("tx_complete"));
        assertMarker(m2, "X1", "500", 1, map("ORDER_LINE_ITEMS", 1));
        assertTrue(m2.getBoolean("tx_complete"));
        assertEquals(json("['" + pos(1) + "','" + pos(2) + "']"), collection(m1, "ORDER_LINE_ITEMS").getJsonArray("pos"));
        assertEquals(json("['" + pos(3) + "']"), collection(m2, "ORDER_LINE_ITEMS").getJsonArray("pos"));
    }

    @Test
    public void fragmentCountsOnlyItsOwnOperations() {
        // the last 1 of 3 ORDERS operations of a split source transaction
        List<DsOperation> ops = new ArrayList<>();
        ops.add(op("X1", "500", ORDERS));

        JsonObject m = (JsonObject) format(delivered("X1", "500", ops));

        assertMarker(m, "X1", "500", 1, map("ORDERS", 1));
        assertTrue(m.getBoolean("tx_complete"));
    }

    @Test
    public void splitTransactionGroupedWithOthers() {
        // one delivery: the end of X1, all of X2, the start of X3
        List<DsOperation> ops = new ArrayList<>();
        ops.add(notLast(op("X1", "500", ORDERS)));
        ops.add(op("X1", "500", ORDERS));
        ops.add(notLast(op("X2", "600", ORDERS)));
        ops.add(op("X2", "600", DETAILS));
        ops.add(notLast(op("X3", "700", ITEMS)));
        ops.add(notLast(op("X3", "700", ITEMS)));

        JsonArray markers = (JsonArray) format(delivered("X1", "500", ops));

        assertEquals(3, markers.size());
        assertTrue(markers.getJsonObject(0).getBoolean("tx_complete"));
        assertTrue(markers.getJsonObject(1).getBoolean("tx_complete"));
        assertFalse(markers.getJsonObject(2).getBoolean("tx_complete"));
        assertMarker(markers.getJsonObject(2), "X3", "700", 2, map("ORDER_LINE_ITEMS", 2));
    }

    @Test
    public void onlyTheDeliverysLastGroupCanBeIncomplete() {
        // X1 ends inside the delivery even if its last operation does not say so
        List<DsOperation> ops = new ArrayList<>();
        ops.add(notLast(op("X1", "500", ORDERS)));
        ops.add(op("X2", "600", ORDERS));

        JsonArray markers = (JsonArray) format(delivered("X1", "500", ops));

        assertTrue(markers.getJsonObject(0).getBoolean("tx_complete"));
        assertTrue(markers.getJsonObject(1).getBoolean("tx_complete"));
    }

    // ---- markerKeys / markerPos ----

    private static final Set<String> MARKER_FIELDS =
            new HashSet<>(Arrays.asList("xid", "csn", "tx_ts", "event_count", "tx_complete", "data_collections"));

    private JsonObject twoOrders() {
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta(ORDER_COLS, "ORDER_ID"), insert("101"), insert("NEW")));
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta(ORDER_COLS, "ORDER_ID"), insert("102"), insert("NEW")));
        return (JsonObject) format(delivered("X1", "500", ops));
    }

    @Test
    public void keysAndPosAreOffByDefault() {
        formatter = new TransactionBoundaryEventJsonFormatter();

        JsonObject marker = twoOrders();
        JsonObject dc = collection(marker, "ORDERS");

        assertEquals(MARKER_FIELDS, marker.keySet());
        assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count")), dc.keySet());
        assertEquals(2, dc.getInt("event_count"));
    }

    @Test
    public void bothTrueAddsKeysAndPos() {
        JsonObject dc = collection(twoOrders(), "ORDERS");

        assertEquals(json("[['101'],['102']]"), dc.getJsonArray("key_ids"));
        assertEquals(json("['" + pos(1) + "','" + pos(2) + "']"), dc.getJsonArray("pos"));
    }

    @Test
    public void markerKeysFalseLeavesOutKeyFields() {
        formatter.setMarkerKeys("false");

        JsonObject marker = twoOrders();
        JsonObject dc = collection(marker, "ORDERS");

        assertEquals(MARKER_FIELDS, marker.keySet());
        assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count", "pos")), dc.keySet());
        assertEquals(2, dc.getInt("event_count"));
        assertEquals(json("['" + pos(1) + "','" + pos(2) + "']"), dc.getJsonArray("pos"));
    }

    @Test
    public void markerPosFalseLeavesOutPos() {
        formatter.setMarkerPos("false");

        JsonObject dc = collection(twoOrders(), "ORDERS");

        assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count", "key_columns", "key_ids")), dc.keySet());
        assertEquals(json("[['101'],['102']]"), dc.getJsonArray("key_ids"));
    }

    @Test
    public void bothOffLeavesOnlyCounts() {
        formatter.setMarkerKeys("false");
        formatter.setMarkerPos("false");

        JsonObject marker = twoOrders();
        JsonObject dc = collection(marker, "ORDERS");

        assertEquals(MARKER_FIELDS, marker.keySet());
        assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count")), dc.keySet());
        assertEquals(2, dc.getInt("event_count"));
    }

    @Test
    public void markerKeysFalseSkipsKeyChecks() {
        formatter.setMarkerKeys("false");
        List<DsOperation> ops = new ArrayList<>();
        // no primary key, and a NULL in what would be the key
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_INSERT, meta(ORDER_COLS), insert(null), insert("NEW")));

        JsonObject dc = collection((JsonObject) format(delivered("X1", "500", ops)), "ORDERS");

        assertEquals(json("['" + pos(1) + "']"), dc.getJsonArray("pos"));
    }

    @Test
    public void markerKeysFalseStillAbendsOnTruncate() {
        formatter.setMarkerKeys("false");
        formatter.setMarkerPos("false");
        List<DsOperation> ops = new ArrayList<>();
        ops.add(keyedOp("X1", "500", ORDERS, OpType.DO_TRUNCATE, meta(ORDER_COLS, "ORDER_ID")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> format(delivered("X1", "500", ops)));

        assertMessage(e, "APPUSER.ORDERS", "DO_TRUNCATE", "truncates must not be captured");
    }

    @Test
    public void flagValuesAreCaseInsensitiveAndTrimmed() {
        formatter.setMarkerKeys(" FALSE ");
        formatter.setMarkerPos("True");

        JsonObject dc = collection(twoOrders(), "ORDERS");

        assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count", "pos")), dc.keySet());
    }

    @Test
    public void invalidFlagValueFailsAtStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> formatter.setMarkerKeys("no"));
        assertMessage(e, "markerKeys", "'no'", "true or false");

        e = assertThrows(IllegalStateException.class, () -> formatter.setMarkerPos(""));
        assertMessage(e, "markerPos", "true or false");
    }

    private static DsOperation notLast(DsOperation op) {
        when(op.isLast()).thenReturn(false);
        return op;
    }

    // ---- helpers ----

    private DsOperation op(String xid, String csn, String table) {
        return op(xid, csn, table, "2026-11-07 21:24:36.000000");
    }

    /** An insert on a table keyed by a single column ID, whose value is the operation's seqno. */
    private DsOperation op(String xid, String csn, String table, String ts) {
        DsOperation op = keyedOp(xid, csn, table, OpType.DO_INSERT, meta(new String[]{"ID"}, "ID"),
                insert(String.valueOf(seqno + 1)));
        when(op.getTimestamp()).thenReturn(new DateString(ts));
        return op;
    }

    /** An operation whose column i (in table metadata order) is cols[i]. */
    private DsOperation keyedOp(String xid, String csn, String table, OpType type, TableMetaData meta, DsColumn... cols) {
        DsOperation op = mock(DsOperation.class);
        when(op.getXidStr()).thenReturn(xid);
        when(op.getCsnStr()).thenReturn(csn);
        when(op.getTableName()).thenReturn(new TableName(table));
        when(op.getOperationSeqno()).thenReturn(++seqno);
        when(op.getTimestamp()).thenReturn(new DateString("2026-11-07 21:24:36.000000"));
        when(op.getOperationType()).thenReturn(type);
        when(op.getPosition()).thenReturn(pos(seqno));
        when(op.getTableMetaData()).thenReturn(meta);
        when(op.isLast()).thenReturn(true);
        for (int i = 0; i < cols.length; i++) {
            when(op.getColumn(i)).thenReturn(cols[i]);
        }
        when(op.getColumns()).thenReturn(Arrays.asList(cols));
        return op;
    }

    /** Trail position of the n-th test operation: sequence 1, offset 4321 + 266 * (n - 1). */
    private static String pos(int n) {
        return String.format("%010d%010d", 1, 4321 + 266 * (n - 1));
    }

    /** Table metadata with the given columns (all NOT NULL) and primary key columns. */
    private static TableMetaData meta(String[] columns, String... keyColumns) {
        return meta(columns, new HashSet<String>(), keyColumns);
    }

    private static TableMetaData meta(String[] columns, Set<String> nullable, String... keyColumns) {
        TableMetaData meta = mock(TableMetaData.class);
        List<ColumnMetaData> all = new ArrayList<>();
        Map<String, ColumnMetaData> byName = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            ColumnMetaData c = mock(ColumnMetaData.class);
            when(c.getColumnName()).thenReturn(columns[i]);
            when(c.getIndex()).thenReturn(i);
            when(c.isNullable()).thenReturn(nullable.contains(columns[i]));
            all.add(c);
            byName.put(columns[i], c);
        }
        List<ColumnMetaData> keys = new ArrayList<>();
        for (String k : keyColumns) keys.add(byName.get(k));
        when(meta.getColumnMetaData()).thenReturn(all);
        when(meta.getKeyColumns()).thenReturn(keys);
        when(meta.getNumKeyColumns()).thenReturn(keys.size());
        when(meta.isPseudoKey()).thenReturn(keys.isEmpty());
        return meta;
    }

    /** A column with only an after image. */
    private static DsColumn insert(String after) {
        DsColumn col = mock(DsColumn.class);
        DsColumn a = value(after);
        when(col.hasAfterValue()).thenReturn(true);
        when(col.getAfter()).thenReturn(a);
        when(col.hasBeforeValue()).thenReturn(false);
        return col;
    }

    /** A column with only a before image. */
    private static DsColumn beforeOnly(String before) {
        DsColumn col = mock(DsColumn.class);
        DsColumn b = value(before);
        when(col.hasBeforeValue()).thenReturn(true);
        when(col.getBefore()).thenReturn(b);
        when(col.hasAfterValue()).thenReturn(false);
        return col;
    }

    /** A column with both images. */
    private static DsColumn both(String before, String after) {
        DsColumn col = mock(DsColumn.class);
        DsColumn b = value(before);
        DsColumn a = value(after);
        when(col.hasBeforeValue()).thenReturn(true);
        when(col.getBefore()).thenReturn(b);
        when(col.hasAfterValue()).thenReturn(true);
        when(col.getAfter()).thenReturn(a);
        return col;
    }

    /** A column absent from both images (not captured). */
    private static DsColumn missing() {
        DsColumn col = mock(DsColumn.class);
        when(col.hasBeforeValue()).thenReturn(false);
        when(col.hasAfterValue()).thenReturn(false);
        when(col.isMissing()).thenReturn(true);
        return col;
    }

    /** One image of a column; null means SQL NULL. */
    private static DsColumn value(String v) {
        DsColumn c = mock(DsColumn.class);
        when(c.isValueNull()).thenReturn(v == null);
        when(c.getValue()).thenReturn(v);
        when(c.isMissing()).thenReturn(false);
        return c;
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
        assertShape(m);
    }

    /** Marker shape: exactly these fields, no more, no fewer. */
    private static void assertShape(JsonObject m) {
        assertEquals(new HashSet<>(Arrays.asList("xid", "csn", "tx_ts", "event_count", "tx_complete", "data_collections")), m.keySet());
        for (JsonValue v : m.getJsonArray("data_collections")) {
            JsonObject dc = (JsonObject) v;
            assertEquals(new HashSet<>(Arrays.asList("data_collection", "event_count", "key_columns", "key_ids", "pos")), dc.keySet());
            assertEquals(dc.getInt("event_count"), dc.getJsonArray("key_ids").size());
            assertEquals(dc.getInt("event_count"), dc.getJsonArray("pos").size());
        }
    }

    private static JsonObject collection(JsonObject marker, String name) {
        for (JsonValue v : marker.getJsonArray("data_collections")) {
            JsonObject dc = (JsonObject) v;
            if (dc.getString("data_collection").equals(name)) return dc;
        }
        throw new AssertionError("no data collection " + name + " in " + marker);
    }

    /** Parses a JSON literal written with single quotes, for compact expected values. */
    private static JsonArray json(String singleQuoted) {
        try (JsonReader reader = Json.createReader(new StringReader(singleQuoted.replace('\'', '"')))) {
            return reader.readArray();
        }
    }
}
