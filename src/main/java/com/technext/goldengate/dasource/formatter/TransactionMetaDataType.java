package com.technext.goldengate.dasource.formatter;

public enum TransactionMetaDataType {
    XID("xid"),
    CSN("csn"),
    TX_TS("tx_ts"),
    EVENT_COUNT("event_count"),
    DATA_COLLECTION("data_collection"),
    DATA_COLLECTIONS("data_collections");

    private final String value;

    TransactionMetaDataType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
