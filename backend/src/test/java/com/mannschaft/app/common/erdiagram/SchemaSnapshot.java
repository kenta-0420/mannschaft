package com.mannschaft.app.common.erdiagram;

import java.util.List;

/**
 * information_schema から読み取ったスキーマの写し（ER 図生成の入力）。
 *
 * <p>JDBC に依存しない純データ。単体テストはこの型を直接組み立てて {@link ErDiagramRenderer} を検証する。
 */
record SchemaSnapshot(List<Table> tables, List<ForeignKey> foreignKeys) {

    /** テーブルまたはビュー。 */
    record Table(String name, boolean view, List<Column> columns, List<String> primaryKey,
                 List<String> uniqueColumns) {
    }

    /**
     * 列定義。
     *
     * @param dataType   DATA_TYPE（例 varchar）
     * @param columnType COLUMN_TYPE（例 varchar(255)）
     * @param defaultValue COLUMN_DEFAULT。null は「NULL 既定」または「既定なし」（{@code nullable} で区別する）
     * @param extra      EXTRA（auto_increment / DEFAULT_GENERATED 等）
     */
    record Column(String name, String dataType, String columnType, boolean nullable, String defaultValue,
                  String extra, String comment) {
    }

    /** 1 制約 = 1 件（複合 FK は列を順序付きで持つ）。 */
    record ForeignKey(String table, String constraintName, List<String> columns, String refTable,
                      List<String> refColumns) {
    }
}
