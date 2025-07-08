package com.technext.goldengate.dasource.formatter;

import oracle.goldengate.datasource.DsOperation;
import oracle.goldengate.datasource.DsTransaction;
import oracle.goldengate.datasource.format.NgBAOSFormattedData;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.datasource.meta.TableName;
import oracle.goldengate.util.DateString;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

public class TransactionBoundaryEventJsonFormatterTest {

    @Mock
    private DsTransaction mockTransaction;

    @Mock
    private DsOperation mockOperation1;

    @Mock
    private DsOperation mockOperation2;

    @Mock
    private TableMetaData mockTableMetaData;

    private TransactionBoundaryEventJsonFormatter formatter;
    private NgBAOSFormattedData formattedData;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        formatter = new TransactionBoundaryEventJsonFormatter();
        formattedData = new NgBAOSFormattedData();

        // Mock transaction data
        when(mockTransaction.getXidStr()).thenReturn("TEST-XID-001");
        when(mockTransaction.getCsnStr()).thenReturn("123456");
        when(mockTransaction.getTransactionBeginTime()).thenReturn(new DateString("2023-12-01 10:00:00.000"));
        when(mockTransaction.getTotalOps()).thenReturn(2);

        // Mock operations
        TableName table1 = new TableName("SCHEMA1.TABLE1");
        TableName table2 = new TableName("SCHEMA1.TABLE1");
        when(mockOperation1.getTableName()).thenReturn(table1);
        when(mockOperation2.getTableName()).thenReturn(table2);
        when(mockOperation1.getOperationSeqno()).thenReturn(1);
        when(mockOperation2.getOperationSeqno()).thenReturn(2);
        when(mockTransaction.getLastOperation()).thenReturn(mockOperation2);
        when(mockTransaction.getOperations()).thenReturn(Arrays.asList(mockOperation1, mockOperation2));
    }

    @Test
    public void testFormatOp() {
        // Execute formatter
        formatter.formatOp(mockTransaction, mockOperation2, mockTableMetaData, formattedData);

        // Parse the output JSON
        String jsonOutput = new String(formattedData.getByteArrayOutputStream(0).toByteArray(), StandardCharsets.UTF_8);
        JsonReader jsonReader = Json.createReader(new ByteArrayInputStream(jsonOutput.getBytes()));
        JsonObject json = jsonReader.readObject();

        // Verify JSON structure
        assertEquals("TEST-XID-001", json.getString("xid"));
        assertEquals("123456", json.getString("csn"));
        assertEquals("2023-12-01 10:00:00.000", json.getString("tx_ts"));
        assertEquals(2, json.getInt("event_count"));

        // Verify data collections
        assertNotNull(json.getJsonArray("data_collections"));
        assertEquals(1, json.getJsonArray("data_collections").size());

        JsonObject dataCollection = json.getJsonArray("data_collections").getJsonObject(0);
        assertEquals("TABLE1", dataCollection.getString("data_collection"));
        assertEquals(2, dataCollection.getInt("event_count"));
    }
}