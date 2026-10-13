package com.mannschaft.app.auth.mfa;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CMP-261012-0910 試練用: 「先行 TX が書き込みを終えたがまだ commit していない」瞬間に後続処理を別スレッドで走らせ、
 * <b>後続が先行のロックを実際に待っていることを MySQL で観測してから</b>先行を commit させる道具。
 *
 * <h2>なぜ必要か</h2>
 * <p>開始点だけを揃えた並列実行では、DB の行ロックや読取りビューが絡む競合が起きる順序に固定できず、修正前の実装でも
 * たまたま緑になりうる。時間（「N 秒以内に終わらなかった」）で待ちを推定するのも不十分で、後続のスレッド起動や
 * コネクション取得が遅れただけでも「待った」と誤認し、先行の commit 後に後続が最初の SELECT を打つ（＝競合が起きない）
 * 順序で緑になりうる（Codex 検分指摘）。</p>
 *
 * <h2>仕組み</h2>
 * <ol>
 *   <li>先行 TX が発行するドメインイベント（{@code DomainEventPublisher#publish} は Spring の同期イベント。先行 TX の
 *       内側・commit 前に届く）を合図に、先行 TX の接続の {@code CONNECTION_ID()} を控え、後続処理を
 *       <b>別スレッド（別 TX）</b>で開始する。</li>
 *   <li>先行を保持したまま、<b>別の管理者接続</b>で {@code performance_schema.data_lock_waits} を
 *       {@code information_schema.INNODB_TRX} と突き合わせ、「先行の接続が持つトランザクションに塞がれて LOCK WAIT 中の
 *       トランザクション」が現れるまで見張る（後続の待ち合わせ SQL も控える）。</li>
 *   <li>観測できたら先行を解放（イベント処理から戻り commit させる）。上限 {@link #OBSERVE_LIMIT} までに観測できない、
 *       または後続が先行の commit 前に終わってしまったら、前提失敗として記録する（{@link #observedLockWait()} が false。
 *       テスト側で落とす。黙って緑にしない）。</li>
 * </ol>
 *
 * <p>イベントの購読は ApplicationContext のマルチキャスタへ実行時に足し、{@link #close()} で外す
 * （TestContext を増やさないため Bean 定義は使わない）。発火は最初の 1 回だけ（後続処理自身が同じイベントを出しても
 * 再帰しない）。</p>
 */
final class ConcurrentOnEvent implements AutoCloseable {

    /** 後続のロック待ちを観測するまでの上限。 */
    static final Duration OBSERVE_LIMIT = Duration.ofSeconds(15);
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** 先行の接続（スレッド ID）が持つトランザクションに塞がれて待っているトランザクション。 */
    private static final String WAITERS_BLOCKED_BY_LEADER_SQL = """
            SELECT r.trx_mysql_thread_id, r.trx_state, r.trx_query
              FROM performance_schema.data_lock_waits w
              JOIN information_schema.INNODB_TRX b ON b.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID
              JOIN information_schema.INNODB_TRX r ON r.trx_id = w.REQUESTING_ENGINE_TRANSACTION_ID
             WHERE b.trx_mysql_thread_id = ?
               AND r.trx_state = 'LOCK WAIT'
            """;

    private final ApplicationEventMulticaster multicaster;
    private final DataSource appDataSource;
    private final String adminJdbcUrl;
    private final String adminUser;
    private final String adminPassword;
    private final ApplicationListener<ApplicationEvent> listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fired = new AtomicBoolean();
    private volatile Future<?> follower;
    private volatile boolean observedLockWait;
    private volatile String observation = "未観測";

    private ConcurrentOnEvent(ApplicationContext context, Class<?> eventType, Callable<?> followerTask,
                              String adminJdbcUrl, String adminUser, String adminPassword) {
        this.multicaster = context.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                ApplicationEventMulticaster.class);
        this.appDataSource = context.getBean(DataSource.class);
        this.adminJdbcUrl = adminJdbcUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        // ラムダだとイベント型の総称が解決できないため、型を明示した匿名クラスで全イベントを受ける
        this.listener = new ApplicationListener<>() {
            @Override
            public void onApplicationEvent(ApplicationEvent event) {
                onEvent(event, eventType, followerTask);
            }
        };
        multicaster.addApplicationListener(listener);
    }

    /**
     * 先行 TX 内で {@code eventType} のイベントが発行された瞬間に {@code followerTask} を別スレッドで開始し、
     * 後続が先行のロックを待つのを観測するまで先行を commit させないよう仕掛ける。
     *
     * @param adminJdbcUrl  観測用の管理者接続の JDBC URL（ロック待ちの参照には PROCESS 権限等が要るため root で繋ぐ）
     * @param adminUser     管理者ユーザー
     * @param adminPassword 管理者パスワード
     */
    static ConcurrentOnEvent arm(ApplicationContext context, Class<?> eventType, Callable<?> followerTask,
                                 String adminJdbcUrl, String adminUser, String adminPassword) {
        return new ConcurrentOnEvent(context, eventType, followerTask, adminJdbcUrl, adminUser, adminPassword);
    }

    private void onEvent(ApplicationEvent event, Class<?> eventType, Callable<?> followerTask) {
        Object payload = event instanceof PayloadApplicationEvent<?> p ? p.getPayload() : event;
        if (!eventType.isInstance(payload) || !fired.compareAndSet(false, true)) {
            return;
        }
        long leaderConnectionId;
        try {
            leaderConnectionId = currentTransactionConnectionId();
        } catch (SQLException e) {
            observation = "先行 TX の接続 ID を取得できなかった: " + e;
            return;
        }
        Future<?> f = executor.submit(followerTask);
        follower = f;
        observe(leaderConnectionId, f);
    }

    /** 先行 TX（このスレッドに束縛された接続）の CONNECTION_ID()。 */
    private long currentTransactionConnectionId() throws SQLException {
        Connection con = DataSourceUtils.getConnection(appDataSource);
        try (PreparedStatement ps = con.prepareStatement("SELECT CONNECTION_ID()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } finally {
            DataSourceUtils.releaseConnection(con, appDataSource);
        }
    }

    /** 後続が先行に塞がれて LOCK WAIT になるのを、上限まで管理者接続で見張る。 */
    private void observe(long leaderConnectionId, Future<?> f) {
        long deadline = System.nanoTime() + OBSERVE_LIMIT.toNanos();
        try (Connection admin = DriverManager.getConnection(adminJdbcUrl, adminUser, adminPassword);
             PreparedStatement ps = admin.prepareStatement(WAITERS_BLOCKED_BY_LEADER_SQL)) {
            ps.setLong(1, leaderConnectionId);
            while (System.nanoTime() < deadline) {
                if (f.isDone()) {
                    observation = "後続が先行の commit 前に終わった（先行のロックを待っていない）";
                    return;
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        observedLockWait = true;
                        observation = "後続（thread=" + rs.getLong(1) + "）が先行（thread=" + leaderConnectionId
                                + "）のロックを " + rs.getString(2) + " で待っている: " + rs.getString(3);
                        return;
                    }
                }
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
            observation = OBSERVE_LIMIT.toSeconds() + " 秒以内に後続のロック待ちを観測できなかった";
        } catch (SQLException e) {
            observation = "ロック待ちの観測に失敗した: " + e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observation = "観測中に割り込まれた";
        }
    }

    /** 合図のイベントが発行され、後続処理が開始されたか。 */
    boolean fired() {
        return fired.get();
    }

    /** 先行を保持している間に、後続が先行のロックを待っているのを MySQL で実測できたか。 */
    boolean observedLockWait() {
        return observedLockWait;
    }

    /** 観測の記録（前提失敗時の説明・待っていた SQL）。 */
    String observation() {
        return observation;
    }

    /** 後続処理の結果（開始されていなければ例外）。 */
    Future<?> follower() {
        if (follower == null) {
            throw new IllegalStateException("合図のイベントが発行されず、後続処理は開始されていない: " + observation);
        }
        return follower;
    }

    @Override
    public void close() {
        multicaster.removeApplicationListener(listener);
        executor.shutdownNow();
    }
}
