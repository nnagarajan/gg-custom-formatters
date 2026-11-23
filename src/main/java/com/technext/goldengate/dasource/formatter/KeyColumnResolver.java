package com.technext.goldengate.dasource.formatter;

import oracle.goldengate.datasource.DsColumn;
import oracle.goldengate.datasource.DsOperation;
import oracle.goldengate.datasource.meta.ColumnMetaData;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.datasource.meta.TableName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Resolves each table's key columns and reads an operation's key values.
 *
 * <p>A table's key columns are its entry in the key column file when it has one, otherwise its primary
 * key from GoldenGate metadata. Resolution and validation happen once per table and are cached until
 * {@link #clear()}.
 *
 * <p>Every failure is an {@link IllegalStateException}: the formatter lets it propagate so Replicat abends.
 */
final class KeyColumnResolver {

    /** A table's resolved key: column names in key order and their column indexes. */
    static final class TableKey {
        final List<String> names;
        final int[] indexes;

        TableKey(List<String> names, int[] indexes) {
            this.names = Collections.unmodifiableList(names);
            this.indexes = indexes;
        }
    }

    private final Consumer<String> warnings;
    private final Map<String, TableKey> cache = new HashMap<>();
    /** Upper-cased SCHEMA.TABLE to upper-cased key column names, from the key column file. */
    private Map<String, List<String>> mapping = Collections.emptyMap();

    KeyColumnResolver(Consumer<String> warnings) {
        this.warnings = warnings;
    }

    /**
     * Loads the key column file: one {@code SCHEMA.TABLE=COL1,COL2} entry per line; blank lines and
     * lines starting with {@code #} or {@code !} are ignored. Names are matched case-insensitively.
     */
    void loadMapping(String path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read key column file " + path + ": " + e, e);
        }
        Map<String, List<String>> parsed = new HashMap<>();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            String table = eq < 0 ? "" : line.substring(0, eq).trim().toUpperCase();
            String[] columns = eq < 0 ? new String[0] : line.substring(eq + 1).split(",", -1);
            List<String> names = new ArrayList<>(columns.length);
            for (String c : columns) {
                String name = c.trim().toUpperCase();
                if (name.isEmpty() || names.contains(name)) {
                    names = null;
                    break;
                }
                names.add(name);
            }
            if (table.isEmpty() || names == null || names.isEmpty() || parsed.containsKey(table)) {
                throw new IllegalStateException("Malformed key column file " + path + " line " + (n + 1)
                        + ": '" + lines.get(n) + "' (expected SCHEMA.TABLE=COL1,COL2 with distinct, non-empty"
                        + " column names and one line per table)");
            }
            parsed.put(table, Collections.unmodifiableList(names));
        }
        mapping = parsed;
        cache.clear();
    }

    /** The operation's table key, resolving and validating it on first sight of the table. */
    TableKey resolve(DsOperation op) {
        checkSupported(op);
        String table = qualifiedName(op.getTableName());
        TableKey key = cache.get(table);
        if (key == null) {
            List<String> mapped = mapping.get(table);
            key = mapped != null ? fromMapping(table, mapped, op.getTableMetaData())
                    : fromMetadata(table, op.getTableMetaData());
            cache.put(table, key);
        }
        return key;
    }

    /** Throws for operations the markers cannot describe: truncates (GETTRUNCATES must be off). */
    static void checkSupported(DsOperation op) {
        if (op.getOperationType() != null && op.getOperationType().isTruncate()) {
            throw new IllegalStateException("Unsupported operation " + op.getOperationType().name() + " on "
                    + qualifiedName(op.getTableName()) + " pos=" + op.getPosition()
                    + ": truncates must not be captured (GETTRUNCATES)");
        }
    }

    /** Forgets every resolved key, so the next operation on each table resolves it again (after DDL). */
    void clear() {
        cache.clear();
    }

    /**
     * The operation's key values in key order. Deletes read the before image; other operations read
     * the after image, falling back to the before image.
     */
    List<String> values(DsOperation op, TableKey key) {
        List<String> values = new ArrayList<>(key.indexes.length);
        boolean delete = op.getOperationType() != null && op.getOperationType().isDelete();
        for (int i = 0; i < key.indexes.length; i++) {
            DsColumn image = image(op.getColumn(key.indexes[i]), delete);
            if (image == null) {
                throw new IllegalStateException(keyError(op, key.names.get(i), "MISSING"));
            }
            if (image.isValueNull()) {
                throw new IllegalStateException(keyError(op, key.names.get(i), "NULL"));
            }
            values.add(image.getValue());
        }
        return values;
    }

    /** Upper-cased SCHEMA.TABLE (or TABLE when there is no schema), as used for matching. */
    static String qualifiedName(TableName table) {
        String schema = table.getSchemaName();
        String name = schema == null || schema.isEmpty() ? table.getShortName() : schema + "." + table.getShortName();
        return name.toUpperCase();
    }

    private TableKey fromMapping(String table, List<String> mapped, TableMetaData meta) {
        Map<String, ColumnMetaData> columns = new HashMap<>();
        if (meta != null && meta.getColumnMetaData() != null) {
            for (ColumnMetaData c : meta.getColumnMetaData()) {
                columns.put(c.getColumnName().toUpperCase(), c);
            }
        }
        int[] indexes = new int[mapped.size()];
        for (int i = 0; i < mapped.size(); i++) {
            ColumnMetaData c = columns.get(mapped.get(i));
            if (c == null) {
                throw new IllegalStateException("Key column file maps " + table + " to " + mapped
                        + ", but " + table + " has no column " + mapped.get(i));
            }
            if (c.isNullable()) {
                warnings.accept("Key column " + table + "." + mapped.get(i) + " is nullable;"
                        + " a NULL value in it will abend Replicat");
            }
            indexes[i] = c.getIndex();
        }
        return new TableKey(new ArrayList<>(mapped), indexes);
    }

    private static TableKey fromMetadata(String table, TableMetaData meta) {
        List<ColumnMetaData> keyColumns = meta == null ? null : meta.getKeyColumns();
        if (meta == null || meta.isPseudoKey() || keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalStateException("Table " + table + " has no primary key and no key column mapping;"
                    + " add it to the key column file (gg.handler.<name>.format.keyColumnsFile)");
        }
        List<String> names = new ArrayList<>(keyColumns.size());
        int[] indexes = new int[keyColumns.size()];
        for (int i = 0; i < keyColumns.size(); i++) {
            names.add(keyColumns.get(i).getColumnName());
            indexes[i] = keyColumns.get(i).getIndex();
        }
        return new TableKey(names, indexes);
    }

    /** The image to read, or null when the value is not present in it. */
    private static DsColumn image(DsColumn column, boolean delete) {
        if (column == null) {
            return null;
        }
        if (!delete && column.hasAfterValue() && present(column.getAfter())) {
            return column.getAfter();
        }
        if (column.hasBeforeValue() && present(column.getBefore())) {
            return column.getBefore();
        }
        return null;
    }

    private static boolean present(DsColumn image) {
        return image != null && !image.isMissing();
    }

    private static String keyError(DsOperation op, String column, String problem) {
        return "Key column " + column + " is " + problem + " for " + qualifiedName(op.getTableName())
                + " op=" + (op.getOperationType() == null ? null : op.getOperationType().name())
                + " pos=" + op.getPosition() + " xid=" + op.getXidStr() + " csn=" + op.getCsnStr();
    }
}
