package com.technext.goldengate.dasource.formatter;

import oracle.goldengate.datasource.DsOperation;
import oracle.goldengate.datasource.DsTransaction;
import oracle.goldengate.datasource.format.NgBAOSFormattedData;
import oracle.goldengate.datasource.meta.DsMetaData;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.datasource.meta.TableName;
import oracle.goldengate.format.NgFormattedData;
import oracle.goldengate.format.json.JsonFormatter;
import oracle.goldengate.util.DateString;

import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * This class extends the JsonFormatter to provide transaction-aware formatting.
 * It formats the transaction information into JSON and writes it to the output stream.
 * The formatted data includes transaction ID, commit sequence number, transaction begin time,
 * total operations, and a count of operations by data collection.
 * Inspired by Debezium Transaction Metadata - https://debezium.io/documentation/reference/stable/connectors/oracle.html#oracle-transaction-metadata
 */
public class TransactionBoundaryEventJsonFormatter extends JsonFormatter {

    @Override
    public void formatOp(DsTransaction dsTransaction, DsOperation dsOperation, TableMetaData tableMetaData, NgFormattedData ngFormattedData) {

    }

    /* * This method is called at the end of a transaction.
     * It formats the transaction information into JSON and writes it to the output stream.
     */
    @Override
    public void endTx(DsTransaction dsTransaction, DsMetaData dsMetaData, NgFormattedData output) {
        NgBAOSFormattedData formattedData = (NgBAOSFormattedData) output;
        JsonObjectBuilder jsonBuilder = this.getJsonProvider().createObjectBuilder();
        formatTransaction(dsTransaction, jsonBuilder);
        writeJson(jsonBuilder, formattedData);
    }

    /**
     * This method is called at the end of the formatter.
     * It writes any remaining data to the output stream.
     */
    private void writeJson(JsonObjectBuilder jsonBuilder, NgBAOSFormattedData formattedData) {
        JsonObject jsonObject = jsonBuilder.build();
        JsonWriter jsonWriter;
        if (this.config.getCharset() == null) {
            jsonWriter = this.getJsonWriterFactory().createWriter(formattedData.getByteArrayOutputStream(0));
        } else {
            jsonWriter = this.getJsonWriterFactory().createWriter(formattedData.getByteArrayOutputStream(0), this.config.getCharset());
        }
        jsonWriter.writeObject(jsonObject);
    }

    /* * This method formats the transaction information into a JSON object.
     * It includes the transaction ID, commit sequence number, transaction begin time,
     * total operations, and a count of operations by data collection.
     */
    private void formatTransaction(DsTransaction dsTransaction, JsonObjectBuilder jsonBuilder) {
        int totalOps = dsTransaction.getTotalOps();
        String xidStr = dsTransaction.getXidStr();
        String csnStr = dsTransaction.getCsnStr();
        DateString transactionBeginTime = dsTransaction.getTransactionBeginTime();
        Map<String, Long> transactionCountByTable = new HashMap<>();
        for (DsOperation dsOperation : dsTransaction.getOperations()) {
            TableName tableName = dsOperation.getTableName();
            transactionCountByTable.putIfAbsent(tableName.getOriginalName(), 0L);
            transactionCountByTable.computeIfPresent(tableName.getOriginalName(), (k, v) -> v + 1);
        }
        jsonBuilder.add("xid", xidStr);
        jsonBuilder.add("csn", csnStr);
        jsonBuilder.add("tnxBeginTs", transactionBeginTime.toString());
        jsonBuilder.add("totalOps", totalOps);
        JsonArrayBuilder arrayBuilder = this.getJsonProvider().createArrayBuilder();
        transactionCountByTable.entrySet().stream().forEach(dsOperation -> {
            JsonObjectBuilder opBuilder = this.getJsonProvider().createObjectBuilder();
            opBuilder.add("data_collection", dsOperation.getKey());
            opBuilder.add("event_count", dsOperation.getValue());
            arrayBuilder.add(opBuilder);
        });
        jsonBuilder.add("data_collections", arrayBuilder.build());
    }

}