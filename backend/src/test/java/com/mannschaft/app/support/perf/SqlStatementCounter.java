package com.mannschaft.app.support.perf;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * データソース層で実行された SQL 文を <b>ステートメント単位</b>で数えるスレッドセーフなカウンタ（測定専用）。
 *
 * <h2>なぜ Hibernate Statistics ではなくこれか（AC-9 計測の勘所）</h2>
 * <p>fan-out 抜本改修 P1 の出陣後、通知の一括 INSERT は IDENTITY 採番が JPA バッチを殺すのを避けるため
 * {@code JdbcTemplate} の多値 INSERT で JPA を迂回する見込みである。{@code JdbcTemplate} 経由の INSERT は
 * {@code org.hibernate.stat.Statistics.getPrepareStatementCount()} には現れない（Hibernate の
 * SessionFactory を通らないため）。したがって「改修前の JPA INSERT」と「改修後の JdbcTemplate INSERT」を
 * <b>同一の物差しで</b>数えるには、両者が必ず通る <b>データソース層</b>（{@link java.sql.PreparedStatement}
 * の {@code execute*} 呼び出し）で計測する必要がある。本カウンタは {@link CountingDataSource} が
 * 全 {@code execute*} をフックして呼び出す先であり、JPA / JdbcTemplate の別を問わず数える。</p>
 *
 * <h2>数え方</h2>
 * <p>{@code PreparedStatement.executeUpdate()} / {@code execute()} / {@code executeBatch()} 等の
 * <b>1 回の呼び出しを 1 ステートメント</b>として数える。受信者ごとに 1 INSERT する現行実装は
 * 受信者数ぶんの {@code executeUpdate} を発行するため線形に増える。多値 INSERT（1 文で複数行）へ
 * 変えると 1 チャンク＝1 呼び出しになるため、発行文数はチャンク数（＝ O(N/チャンクサイズ)）に落ちる。
 * この差が AC-9（バルク化）の red/green 指標になる。</p>
 *
 * <p>マッチャは事前登録した名前付き述語（小文字化済みの SQL を受け取る）で、実行のたびに全述語を評価して
 * 該当カウンタを加算する。{@link #reset()} で全カウンタを 0 に戻す（述語登録は保持）。</p>
 */
public final class SqlStatementCounter {

    private final Map<String, Predicate<String>> matchers = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    /**
     * 名前付き述語を登録する。{@code predicate} は <b>小文字化済み</b>の SQL を受け取る。
     *
     * @param name      カウンタ名
     * @param predicate 小文字化済み SQL に対する述語（true なら該当カウンタを加算）
     */
    public void register(String name, Predicate<String> predicate) {
        matchers.put(name, predicate);
        counts.putIfAbsent(name, new LongAdder());
    }

    /**
     * データソース層から 1 ステートメント実行ごとに呼ばれる。SQL を小文字化し、全登録述語を評価して
     * 該当するカウンタを加算する。
     *
     * @param sql 実行された SQL（null 可＝無視）
     */
    public void onExecute(String sql) {
        if (sql == null) {
            return;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Predicate<String>> e : matchers.entrySet()) {
            if (e.getValue().test(lower)) {
                counts.get(e.getKey()).increment();
            }
        }
    }

    /** 指定カウンタの現在値を返す（未登録なら 0）。 */
    public long count(String name) {
        LongAdder a = counts.get(name);
        return a == null ? 0L : a.sum();
    }

    /** 全カウンタを 0 に戻す（述語登録は保持）。計測区間の直前に呼ぶ。 */
    public void reset() {
        counts.values().forEach(LongAdder::reset);
    }
}
