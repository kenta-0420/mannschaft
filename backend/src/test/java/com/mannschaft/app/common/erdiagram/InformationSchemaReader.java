package com.mannschaft.app.common.erdiagram;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 接続中のスキーマの information_schema を<b>4 本の一括クエリ</b>で読み取る
 * （テーブルごとの逐次クエリはしない）。{@code flyway_schema_history} は対象外。
 */
final class InformationSchemaReader {

    static final String FLYWAY_HISTORY = "flyway_schema_history";

    private InformationSchemaReader() {
    }

    static SchemaSnapshot read(Connection con) throws SQLException {
        String schema;
        try (PreparedStatement ps = con.prepareStatement("SELECT DATABASE()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            schema = rs.getString(1);
        }

        // 1) テーブル/ビュー
        Map<String, Boolean> tableIsView = new TreeMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (FLYWAY_HISTORY.equalsIgnoreCase(name)) {
                        continue;
                    }
                    tableIsView.put(name, "VIEW".equals(rs.getString(2)));
                }
            }
        }

        // 2) 列（ORDINAL_POSITION 順）
        Map<String, List<SchemaSnapshot.Column>> columns = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA,"
                        + " COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?"
                        + " ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new SchemaSnapshot.Column(
                            rs.getString(2), rs.getString(3).toLowerCase(), rs.getString(4),
                            "YES".equals(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8)));
                }
            }
        }

        // 3) 主キー・外部キー（KEY_COLUMN_USAGE）
        Map<String, List<String>> primaryKeys = new TreeMap<>();
        Map<String, SchemaSnapshot.ForeignKey> fks = new TreeMap<>();
        Map<String, List<String>> fkCols = new LinkedHashMap<>();
        Map<String, List<String>> fkRefCols = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION, REFERENCED_TABLE_NAME,"
                        + " REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = ? AND (CONSTRAINT_NAME = 'PRIMARY' OR REFERENCED_TABLE_NAME IS NOT NULL)"
                        + " ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String constraint = rs.getString(2);
                    String column = rs.getString(3);
                    String refTable = rs.getString(5);
                    if ("PRIMARY".equals(constraint) && refTable == null) {
                        primaryKeys.computeIfAbsent(table, k -> new ArrayList<>()).add(column);
                    } else {
                        String key = table + "\u0000" + constraint;
                        fkCols.computeIfAbsent(key, k -> new ArrayList<>()).add(column);
                        fkRefCols.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getString(6));
                        fks.putIfAbsent(key, new SchemaSnapshot.ForeignKey(table, constraint, null, refTable, null));
                    }
                }
            }
        }
        List<SchemaSnapshot.ForeignKey> foreignKeys = new ArrayList<>();
        fks.forEach((key, fk) -> foreignKeys.add(new SchemaSnapshot.ForeignKey(
                fk.table(), fk.constraintName(), fkCols.get(key), fk.refTable(), fkRefCols.get(key))));

        // 4) 単一列 UNIQUE（STATISTICS）。複合 UNIQUE は列単位の UK 印にしない。
        Map<String, Map<String, List<String>>> uniqueIndexes = new TreeMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = ? AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY'"
                        + " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    uniqueIndexes.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                            .computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(3));
                }
            }
        }

        List<SchemaSnapshot.Table> tables = new ArrayList<>();
        tableIsView.forEach((name, view) -> {
            TreeSet<String> unique = new TreeSet<>();
            uniqueIndexes.getOrDefault(name, Map.of()).values().stream()
                    .filter(cols -> cols.size() == 1).forEach(cols -> unique.add(cols.get(0)));
            tables.add(new SchemaSnapshot.Table(name, view, columns.getOrDefault(name, List.of()),
                    primaryKeys.getOrDefault(name, List.of()), new ArrayList<>(unique)));
        });
        return new SchemaSnapshot(tables, foreignKeys);
    }
}
