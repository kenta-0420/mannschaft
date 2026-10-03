package com.mannschaft.app.common.architecture;

/**
 * 照合順序に関するスキーマ規約の単一の正本（issue #2589）。
 *
 * <h2>なぜ定数を 1 箇所に集めるのか</h2>
 * <p>照合順序の値は「migration の統一先」「Testcontainers のサーバ既定」「新規表の番人」
 * 「回帰ガード IT の期待値」の 4 箇所で一致していなければ意味を成さない。
 * 各所にリテラルを散らすと、片方だけ書き換わったときに
 * <b>テストは緑のまま本番だけ壊れる</b>という issue #2589 そのものの再演になる。
 * したがって値をここに集約し、参照側にリテラルを置かない。</p>
 *
 * <h2>本番との対応</h2>
 * <ul>
 *   <li>{@link #PRODUCTION_COLLATION_SERVER} … 本番 RDS のパラメータグループ
 *       {@code collation_server}（{@code infra/terraform/modules/data/main.tf}）と同値。
 *       ローカル {@code docker-compose.yml} の {@code --collation-server} もこの値に揃えてある。</li>
 *   <li>{@link #UNIFIED_COLLATION} … {@code V175.20260804134628__unify_table_collation.sql} が
 *       全表・全文字列列を収束させる先。上と同値にしてあるのは、
 *       「宣言を忘れた新規表がサーバ既定を継承しても、正しい照合順序に着地する」ようにするため
 *       （番人が漏れても被害が出ない多重防御）。</li>
 * </ul>
 */
public final class SchemaCollationPolicy {

    /** スキーマ全体で統一する文字セット。 */
    public static final String UNIFIED_CHARSET = "utf8mb4";

    /**
     * スキーマ全体で統一する照合順序。
     * V175 の migration および本番 RDS の既定と一致していなければならない。
     */
    public static final String UNIFIED_COLLATION = "utf8mb4_0900_ai_ci";

    /**
     * 本番 RDS の {@code collation_server}。
     * 検証用コンテナはこの値で起動し、「本番と同じ照合順序で走る」ことを保証する。
     */
    public static final String PRODUCTION_COLLATION_SERVER = UNIFIED_COLLATION;

    /**
     * 照合順序を統一した migration のバージョン（{@code V<major>.<minor>} の major）。
     *
     * <p>これ以降に追加される migration の {@code CREATE TABLE} は照合順序の明示宣言を強制される。
     * これより前の既存 migration は Flyway のチェックサム制約により書き換えられない
     * （書き換えると適用済み環境が起動不能になる）ため免除する。
     * 免除しても実害が無いのは、V175 が適用後の実スキーマを統一し、
     * {@code SchemaCollationConsistencyIT} が実スキーマ側で全表を検証しているからである。</p>
     */
    public static final int UNIFICATION_MIGRATION_MAJOR = 175;

    private SchemaCollationPolicy() {
    }
}
