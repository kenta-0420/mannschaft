package com.mannschaft.app.common.visibility.perf;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 性能テスト用、テーブル名ベースで SQL クエリ意図単位の数を集計するヘルパ。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §13.4 完全一致。
 *
 * <p>Hibernate の生 SQL 数 ({@code Statistics#getPrepareStatementCount}) は
 * JPQL バッチ分割や IN 句展開で false positive を生むため、テーブル名で意図を識別
 * する本ヘルパで「クエリ意図数」を測定する。これにより Hibernate 6 へのアップグレードや
 * IN バッチ分割の挙動変更でテストが false positive にならない。
 *
 * <p><strong>登録方法</strong>: {@code application-test.yml} の Hibernate プロパティで
 * 本クラスを {@code spring.jpa.properties.hibernate.session_factory.statement_inspector}
 * として登録する。Hibernate 起動時に no-arg コンストラクタでインスタンス化され、
 * 全クエリが {@link #inspect(String)} を経由する。
 *
 * <p><strong>状態保持</strong>: Hibernate がインスタンス化するため、計測結果は
 * 静的フィールドに保持する。テスト実行は同一 JVM 内で逐次的のため、各テストの開始時に
 * {@link #reset()} を呼ぶ運用とする ({@link VisibilityCheckerPerformanceTestBase} の
 * {@code @BeforeEach} で実施)。
 *
 * <p><strong>スレッドセーフ性</strong>: 内部の捕捉リスト操作はすべて {@code synchronized}
 * で保護する。性能テストでは原則単一スレッドだが、Hibernate 内部の保険として保持する。
 */
public class SqlIntentCounter implements StatementInspector {

    /** 捕捉した SQL 文を保持するリストへの参照. */
    private static final AtomicReference<List<String>> CAPTURED_SQL =
        new AtomicReference<>(new ArrayList<>());

    /**
     * Hibernate 統合点。発行直前の SQL 文を捕捉してそのまま返す。
     *
     * @param sql Hibernate が生成した SQL 文
     * @return 元の SQL 文をそのまま返す（書き換えは行わない）
     */
    @Override
    public synchronized String inspect(String sql) {
        CAPTURED_SQL.get().add(sql);
        return sql;
    }

    /**
     * 捕捉した SQL 文をすべてクリアする。各テストの {@code @BeforeEach} で呼ぶ想定。
     */
    public static synchronized void reset() {
        CAPTURED_SQL.set(new ArrayList<>());
    }

    /**
     * 捕捉した SQL 文の総数を返す。
     *
     * @return 直近の {@link #reset()} 以降に Hibernate が発行した SQL 数
     */
    public static synchronized int totalCount() {
        return CAPTURED_SQL.get().size();
    }

    /**
     * テーブル名 (もしくはその一部) を含む SQL の数を返す。大文字小文字は無視する。
     *
     * @param tableHint テーブル名ヒント（例: {@code "blog_posts"}, {@code "user_roles"}）
     * @return ヒントを含む SQL 文の件数
     */
    public static synchronized int intentCount(String tableHint) {
        String lower = tableHint.toLowerCase();
        return (int) CAPTURED_SQL.get().stream()
            .filter(s -> s.toLowerCase().contains(lower))
            .count();
    }

    /**
     * 捕捉した全 SQL 文の不変コピーを返す。デバッグ用途。
     *
     * @return 捕捉した SQL 文のリスト（不変コピー）
     */
    public static synchronized List<String> capturedSqls() {
        return List.copyOf(CAPTURED_SQL.get());
    }
}
