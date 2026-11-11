package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * CMP-260826-1457 W1 前段（招待承諾分離）の実DB試練共通部。
 *
 * <p>新設型（{@code InviteAcceptanceFacade} / {@code InviteAcceptanceWriter}）は試練時点で存在しないため反射で引く。
 * {@link #acceptCompat} / {@link #declineCompat} は Facade があれば Facade、無ければ現行 {@link InviteService} を呼ぶ。
 * 現行挙動を固定する特性試験はこれを使い、main でも緑になる。{@link #facade()} / {@link #writer()} は
 * 存在しなければ assertion で落ちる（red）。</p>
 *
 * <p>契約（仮）: 陣立て書 {@code .claude/campaigns/2026-11-08-cmp1457-invite-accept-split.md} §3・§9。
 * claims の状態は {@code CLAIMED / GRANTED / RELEASED}、主キーは UUIDv7（BINARY(16)）。</p>
 */
abstract class InviteAcceptanceItSupport extends InviteGenerationItSupport {

    static final String FACADE = "com.mannschaft.app.role.service.InviteAcceptanceFacade";
    static final String WRITER = "com.mannschaft.app.role.service.InviteAcceptanceWriter";
    static final String CLAIMS = "invite_token_claims";
    /** 監視 gauge（仮契約）。閾値（既定10分）超過の CLAIMED 件数と最古 CLAIMED の滞留秒。 */
    static final String GAUGE_STALE_COUNT = "invite_token_claims.stale_claimed_count";
    static final String GAUGE_OLDEST_AGE = "invite_token_claims.oldest_claimed_age_seconds";

    @Autowired protected ConfigurableApplicationContext context;
    @Autowired protected InviteService inviteService;

    private final List<Scope> acceptanceScopes = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> acceptanceUsers = Collections.synchronizedList(new ArrayList<>());

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    @Override
    protected Scope scope(String type) {
        Scope scope = super.scope(type);
        acceptanceScopes.add(scope);
        return scope;
    }

    @Override
    protected Long user() {
        Long id = super.user();
        acceptanceUsers.add(id);
        return id;
    }

    /** 非 ACTIVE ユーザー（既定 FROZEN）。 */
    protected Long inactiveUser(String status) {
        Long id = user();
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, id);
        return id;
    }

    protected Long roleId(Scope scope, String name, int priority) {
        actorWithRole(scope, name, priority); // role 行の確保と後始末登録を兼ねる
        return roles.findByName(name).orElseThrow().getId();
    }

    protected Long folder(Long userId, String scopeType) {
        jdbc.update("INSERT INTO my_scope_folders (user_id, scope_type, name, sort_order, is_default, created_at, updated_at) VALUES (?, ?, ?, 0, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                userId, scopeType, "試練フォルダ");
        return jdbc.queryForObject("SELECT MAX(id) FROM my_scope_folders WHERE user_id = ?", Long.class, userId);
    }

    protected void block(Scope scope, Long userId, Long blockedBy) {
        if ("TEAM".equals(scope.type())) {
            jdbc.update("INSERT INTO team_blocks (team_id, user_id, blocked_by, created_at) VALUES (?, ?, ?, UTC_TIMESTAMP())",
                    scope.id(), userId, blockedBy);
        } else {
            jdbc.update("INSERT INTO organization_blocks (organization_id, user_id, blocked_by, created_at) "
                    + "VALUES (?, ?, ?, UTC_TIMESTAMP())", scope.id(), userId, blockedBy);
        }
    }

    protected void closeScope(Scope scope) {
        jdbc.update("UPDATE " + scope.table() + " SET archived_at = UTC_TIMESTAMP(), closure_generation = 1 WHERE id = ?",
                scope.id());
    }

    protected void insertMembership(Scope scope, Long userId, String roleKind) {
        jdbc.update("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) VALUES (?, ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP(), UTC_TIMESTAMP())",
                userId, scope.type(), scope.id(), roleKind);
    }

    protected void insertUserRole(Scope scope, Long userId) {
        String column = "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
        jdbc.update("INSERT INTO user_roles (user_id, role_id, " + column + ", granted_by, created_at, updated_at) VALUES (?, ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())",
                userId, scope.memberRoleId(), scope.id(), userId);
    }

    /**
     * 本番の {@code uq_user_roles_user_scope}（V2.006: user_id と生成列 scope_key の一意制約）を、この試験が所有する
     * 1ユーザーに限って Entity 由来スキーマ（ddl-auto=create。UserRoleEntity は一意制約を持たない）へ再現する。
     * 関数索引は他ユーザーの行では NULL になるので、既存行・他試験に影響しない。終わったら必ず
     * {@link #dropUserRoleScopeUnique(String)} で外す。
     */
    protected String addUserRoleScopeUnique(Long userId) {
        String name = "uq_it_user_roles_" + userId;
        jdbc.execute("ALTER TABLE user_roles ADD UNIQUE INDEX " + name + " ((CASE WHEN user_id = " + userId
                + " THEN CONCAT(COALESCE(team_id, 0), ':', COALESCE(organization_id, 0)) END))");
        return name;
    }

    protected void dropUserRoleScopeUnique(String name) {
        jdbc.execute("ALTER TABLE user_roles DROP INDEX " + name);
    }

    // ------------------------------------------------------------------
    // 承諾・辞退の呼び出し
    // ------------------------------------------------------------------

    protected static Optional<Class<?>> findClass(String name) {
        try {
            return Optional.of(Class.forName(name));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    protected static Class<?> requireClass(String name) {
        return findClass(name).orElseGet(() -> fail("未実装: " + name + "（CMP-1457 陣立て書 §3）"));
    }

    protected Object facade() {
        return context.getBean(requireClass(FACADE));
    }

    protected Object writer() {
        return context.getBean(requireClass(WRITER));
    }

    /** Facade があれば Facade、無ければ現行 InviteService で承諾する（特性試験用）。 */
    protected void acceptCompat(String token, Long userId, Long folderId) {
        Optional<Class<?>> facade = findClass(FACADE);
        if (facade.isPresent()) {
            call(context.getBean(facade.get()), facade.get(), "joinByInvite",
                    new Class<?>[] {String.class, Long.class, Long.class}, token, userId, folderId);
        } else {
            call(inviteService, InviteService.class, "joinByInvite",
                    new Class<?>[] {String.class, Long.class, Long.class}, token, userId, folderId);
        }
    }

    protected void acceptCompat(String token, Long userId) {
        Optional<Class<?>> facade = findClass(FACADE);
        if (facade.isPresent()) {
            call(context.getBean(facade.get()), facade.get(), "joinByInvite",
                    new Class<?>[] {String.class, Long.class}, token, userId);
        } else {
            call(inviteService, InviteService.class, "joinByInvite",
                    new Class<?>[] {String.class, Long.class}, token, userId);
        }
    }

    protected void declineCompat(String token, Long userId) {
        Optional<Class<?>> facade = findClass(FACADE);
        if (facade.isPresent()) {
            call(context.getBean(facade.get()), facade.get(), "declineInvite",
                    new Class<?>[] {String.class, Long.class}, token, userId);
        } else {
            call(inviteService, InviteService.class, "declineInvite",
                    new Class<?>[] {String.class, Long.class}, token, userId);
        }
    }

    /** Facade 経由でのみ承諾する（新設型が無ければ red）。 */
    protected void accept(String token, Long userId, Long folderId) {
        Class<?> type = requireClass(FACADE);
        call(context.getBean(type), type, "joinByInvite",
                new Class<?>[] {String.class, Long.class, Long.class}, token, userId, folderId);
    }

    protected void accept(String token, Long userId) {
        accept(token, userId, null);
    }

    protected void releaseClaim(UUID claimId) {
        Class<?> type = requireClass(WRITER);
        call(context.getBean(type), type, "releaseClaim", new Class<?>[] {UUID.class}, claimId);
    }

    protected void recordJoined(UUID claimId) {
        Class<?> type = requireClass(WRITER);
        call(context.getBean(type), type, "recordJoined", new Class<?>[] {UUID.class}, claimId);
    }

    protected static Object call(Object target, Class<?> declared, String name, Class<?>[] types, Object... args) {
        Method method;
        try {
            method = declared.getMethod(name, types);
        } catch (NoSuchMethodException e) {
            return fail("未実装: " + declared.getName() + "#" + name);
        }
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static BusinessException business(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e;
        }
        return fail("BusinessException が投げられなかった");
    }

    protected static void assertError(BusinessException e, ErrorCode code, HttpStatus override) {
        assertThat(e.getErrorCode()).isEqualTo(code);
        assertThat(e.getHttpStatusOverride()).isEqualTo(override);
    }

    // ------------------------------------------------------------------
    // 観測
    // ------------------------------------------------------------------

    protected int usedCount(Long tokenId) {
        return jdbc.queryForObject("SELECT used_count FROM invite_tokens WHERE id = ?", Integer.class, tokenId);
    }

    protected LocalDateTime revokedAt(Long tokenId) {
        return tokens.findById(tokenId).orElseThrow().getRevokedAt();
    }

    protected long userRoleCount(Long userId, Scope scope) {
        String column = "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND " + column + " = ?",
                Long.class, userId, scope.id());
    }

    protected long membershipCount(Long userId, Scope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ? AND scope_type = ? AND scope_id = ? "
                + "AND left_at IS NULL", Long.class, userId, scope.type(), scope.id());
    }

    protected List<String> folderPlacements(Long userId, Scope scope) {
        return jdbc.queryForList("SELECT i.assigned_via FROM my_scope_folder_items i JOIN my_scope_folders f ON f.id = i.folder_id "
                + "WHERE f.user_id = ? AND f.scope_type = ? AND i.scope_id = ?", String.class, userId, scope.type(), scope.id());
    }

    protected boolean claimsTableExists() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ?", Long.class, CLAIMS);
        return count != null && count > 0;
    }

    protected void requireClaimsTable() {
        assertThat(claimsTableExists()).as("未実装: " + CLAIMS + " 表（陣立て書 §9.1）").isTrue();
    }

    protected record Claim(UUID id, Long tokenId, Long userId, String status) { }

    /** 当該トークンの claims（作成順）。表が無ければ red。 */
    protected List<Claim> claims(Long tokenId) {
        requireClaimsTable();
        return jdbc.query("SELECT LOWER(HEX(id)) AS hex_id, token_id, user_id, status FROM " + CLAIMS
                        + " WHERE token_id = ? ORDER BY created_at, id",
                (rs, i) -> new Claim(uuid(rs.getString("hex_id")), rs.getLong("token_id"), rs.getLong("user_id"),
                        rs.getString("status")),
                tokenId);
    }

    /** 特性試験用: 表がまだ無い（main）なら 0。 */
    protected long claimCountOrZero(Long tokenId) {
        if (!claimsTableExists()) {
            return 0;
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + CLAIMS + " WHERE token_id = ?", Long.class, tokenId);
    }

    /**
     * 停止・取り残しを模した claim 行を直接置く（UTC 壁時計で created_at を指定）。
     * {@code bumpUsedCount} なら §9.5 の不変式どおり used_count も 1 進める（claim の T6 commit 相当）。
     */
    protected UUID insertClaim(Long tokenId, Long userId, String status, LocalDateTime createdAtUtc, boolean bumpUsedCount) {
        requireClaimsTable();
        UUID id = com.mannschaft.app.common.UuidV7.generate();
        jdbc.update("INSERT INTO " + CLAIMS + " (id, token_id, user_id, status, created_at, updated_at) "
                        + "VALUES (UNHEX(?), ?, ?, ?, ?, ?)",
                id.toString().replace("-", ""), tokenId, userId, status, createdAtUtc, createdAtUtc);
        if (bumpUsedCount) {
            jdbc.update("UPDATE invite_tokens SET used_count = used_count + 1 WHERE id = ?", tokenId);
        }
        return id;
    }

    protected static LocalDateTime utcNow() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    protected String claimStatus(UUID claimId) {
        requireClaimsTable();
        List<String> found = jdbc.queryForList("SELECT status FROM " + CLAIMS + " WHERE id = UNHEX(?)", String.class,
                claimId.toString().replace("-", ""));
        return found.isEmpty() ? null : found.get(0);
    }

    protected List<String> claimStatuses(Long tokenId) {
        return claims(tokenId).stream().map(Claim::status).toList();
    }

    protected static UUID uuid(String hex) {
        String h = hex.toLowerCase();
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                + h.substring(16, 20) + "-" + h.substring(20));
    }

    /** 監査イベント（TeamMemberAuditEvent / OrganizationMemberAuditEvent）を対象ユーザーで捕捉する。 */
    protected final class AuditCapture implements AutoCloseable {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private final ApplicationListener<PayloadApplicationEvent<?>> listener;
        private final ApplicationEventMulticaster multicaster;

        AuditCapture(Long targetUserId) {
            listener = event -> {
                Object payload = event.getPayload();
                if (payload instanceof TeamMemberAuditEvent team && targetUserId.equals(team.getTargetUserId())) {
                    events.add("TEAM:" + team.getSubType());
                } else if (payload instanceof OrganizationMemberAuditEvent org
                        && targetUserId.equals(org.getTargetUserId())) {
                    events.add("ORGANIZATION:" + org.getSubType());
                }
            };
            multicaster = context.getBean("applicationEventMulticaster", ApplicationEventMulticaster.class);
            multicaster.addApplicationListener(listener);
        }

        public List<String> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }

        @Override
        public void close() {
            multicaster.removeApplicationListener(listener);
        }
    }

    protected AuditCapture capture(Long targetUserId) {
        return new AuditCapture(targetUserId);
    }

    /** 新規成功時の招待監査（TEAM は JOINED+INVITED、ORG は JOINED×2）。 */
    protected static List<String> successAudit(String type) {
        return "TEAM".equals(type) ? List.of("TEAM:JOINED", "TEAM:INVITED")
                : List.of("ORGANIZATION:JOINED", "ORGANIZATION:JOINED");
    }

    /** 招待監査（T9 由来）。TEAM は INVITED、ORG は 2 本目の JOINED。 */
    protected static long inviteAuditCount(String type, List<String> events) {
        if ("TEAM".equals(type)) {
            return events.stream().filter("TEAM:INVITED"::equals).count();
        }
        return Math.max(0, events.stream().filter("ORGANIZATION:JOINED"::equals).count() - 1);
    }

    protected <T> T inNewTx(java.util.function.Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    // ------------------------------------------------------------------
    // 同期・ロック観測
    // ------------------------------------------------------------------

    protected long connectionId() {
        return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    /** 既存 InviteGenerationConsistencyIT と同じ performance_schema による実ロック待機観測。 */
    protected void awaitRowWait(String table, long blockerConnection, Long rowId, Future<?> worker) {
        awaitRowWait(table, blockerConnection, rowId, worker, null, null, 0);
    }

    /** C7 の失敗時だけ、同期点を解放する前に限定診断を残す。待機の合格条件・期限は変更しない。 */
    protected void awaitRowWait(String table, long blockerConnection, Long rowId, Future<?> worker,
                                AtomicReference<Thread> workerThread, ThreadPoolExecutor executor,
                                long blockerLockedAtNanos) {
        String url = MYSQL.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?")
                + "connectTimeout=1000&socketTimeout=1000&useSSL=false&allowPublicKeyRetrieval=true";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var observer = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                     SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                     JOIN performance_schema.data_locks requested
                       ON requested.ENGINE = waits.ENGINE
                      AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                     JOIN performance_schema.threads blocker ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
                     WHERE requested.OBJECT_SCHEMA = ? AND requested.OBJECT_NAME = ?
                       AND requested.LOCK_TYPE = 'RECORD'
                       AND requested.LOCK_DATA LIKE ? AND blocker.PROCESSLIST_ID = ?
                     """)) {
            query.setQueryTimeout(1);
            query.setString(1, MYSQL.getDatabaseName());
            query.setString(2, table);
            query.setString(3, "%" + rowId + "%");
            query.setLong(4, blockerConnection);
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) {
                    if (rows.next() && rows.getLong(1) > 0) {
                        return;
                    }
                }
                if (worker.isDone()) {
                    try {
                        worker.get();
                    } catch (ExecutionException failure) {
                        throw new AssertionError("対象行の実待機前に worker が失敗しました", failure);
                    }
                    throw new AssertionError("対象行の実待機前に worker が終了しました");
                }
                Thread.sleep(25);
            }
            AssertionError failure = new AssertionError("10秒以内に " + table + " 行の実ロック待機を観測できませんでした");
            if (workerThread != null) {
                throw RowWaitDiagnostics.preserveFailure(failure,
                        () -> rowWaitDiagnostics(observer, blockerConnection, rowId, worker, workerThread,
                                executor, blockerLockedAtNanos), System.err::println);
            }
            throw failure;
        } catch (SQLException failure) {
            throw new AssertionError("待機観測接続に失敗しました", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("ロック観測が中断されました", failure);
        }
    }

    /** SQL より先に Java 側を採取し、診断自身が遅れても採取時点を見失わないようにする。 */
    private String rowWaitDiagnostics(Connection observer, long blockerConnection, Long rowId, Future<?> worker,
                                      AtomicReference<Thread> workerThread, ThreadPoolExecutor executor,
                                      long blockerLockedAtNanos) {
        StringBuilder report = new StringBuilder("[invite-row-wait-timeout]\n");
        long snapshotAt = System.nanoTime();
        report.append("blockerConnection=").append(blockerConnection).append('\n');
        report.append("workerDone=").append(worker.isDone()).append(" workerCancelled=")
                .append(worker.isCancelled()).append('\n');
        if (blockerLockedAtNanos == 0) {
            report.append("blockerLatchRemainingMs=UNKNOWN lockTimestamp=UNKNOWN\n");
        } else {
            long elapsed = snapshotAt - blockerLockedAtNanos;
            report.append("blockerLockElapsedMs=").append(TimeUnit.NANOSECONDS.toMillis(elapsed))
                    .append(" blockerLatch20sMinusLockElapsedMs=")
                    .append(20_000 - TimeUnit.NANOSECONDS.toMillis(elapsed))
                    .append(" blockerLatchActualRemainingMs=UNKNOWN\n");
        }
        Thread thread = workerThread.get();
        if (thread == null) {
            report.append("workerThread=NOT_STARTED\n");
        } else {
            report.append("workerThreadId=").append(thread.threadId()).append(" state=").append(thread.getState())
                    .append('\n');
            StackTraceElement[] frames = thread.getStackTrace();
            for (int i = 0; i < Math.min(24, frames.length); i++) {
                StackTraceElement frame = frames[i];
                report.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append('(').append(frame.getFileName()).append(':').append(frame.getLineNumber()).append(")\n");
            }
        }
        if (executor != null) {
            report.append("executorPool=").append(executor.getPoolSize()).append(" active=")
                    .append(executor.getActiveCount()).append(" queued=").append(executor.getQueue().size())
                    .append(" completed=").append(executor.getCompletedTaskCount()).append(" tasks=")
                    .append(executor.getTaskCount()).append('\n');
        }
        // 作成済み singleton だけを見る。Bean 生成・接続の借用・DataSource の unwrap は行わない。
        int poolNumber = 0;
        for (String name : context.getBeanFactory().getSingletonNames()) {
            if (context.getBeanFactory().getSingleton(name) instanceof HikariDataSource source) {
                var pool = source.getHikariPoolMXBean();
                if (pool != null && poolNumber++ < 4) {
                    report.append("hikari#").append(poolNumber).append(" active=").append(pool.getActiveConnections())
                            .append(" idle=").append(pool.getIdleConnections()).append(" total=")
                            .append(pool.getTotalConnections()).append(" awaiting=")
                            .append(pool.getThreadsAwaitingConnection()).append('\n');
                }
            }
        }
        report.append("hikariObserved=").append(Math.min(poolNumber, 4)).append('\n');
        // LOCK_DATA の原文は ResultSet に返さない。二次索引にはトークンのハッシュが含まれ得る。
        String dataClass = """
                CASE WHEN locks.LOCK_DATA IS NULL THEN 'NULL'
                     WHEN locks.OBJECT_NAME = 'invite_tokens' AND locks.INDEX_NAME = 'PRIMARY'
                          AND locks.LOCK_DATA = ? THEN 'PRIMARY_TARGET'
                     WHEN locks.INDEX_NAME = 'PRIMARY' THEN 'PRIMARY_OTHER'
                     ELSE 'NONPRIMARY_REDACTED' END AS data_class
                """;
        String held = "SELECT locks.OBJECT_NAME, locks.INDEX_NAME, locks.LOCK_TYPE, locks.LOCK_MODE, "
                + "locks.LOCK_STATUS, owner.PROCESSLIST_ID AS connection_id, " + dataClass + """
                 FROM performance_schema.data_locks locks
                 JOIN performance_schema.threads owner ON owner.THREAD_ID = locks.THREAD_ID
                 WHERE locks.OBJECT_SCHEMA = ? AND owner.PROCESSLIST_ID = ? LIMIT 16
                """;
        String requested = "SELECT locks.OBJECT_NAME, locks.INDEX_NAME, locks.LOCK_TYPE, locks.LOCK_MODE, "
                + "locks.LOCK_STATUS, requester.PROCESSLIST_ID AS connection_id, " + dataClass + """
                 FROM performance_schema.data_lock_waits waits
                 JOIN performance_schema.data_locks locks
                   ON locks.ENGINE = waits.ENGINE AND locks.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                 JOIN performance_schema.threads blocker ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
                 LEFT JOIN performance_schema.threads requester ON requester.THREAD_ID = waits.REQUESTING_THREAD_ID
                 WHERE locks.OBJECT_SCHEMA = ? AND blocker.PROCESSLIST_ID = ? LIMIT 16
                """;
        appendLockDiagnostics(report, "held", observer, held, blockerConnection, rowId);
        appendLockDiagnostics(report, "requested", observer, requested, blockerConnection, rowId);
        return report.toString();
    }

    private void appendLockDiagnostics(StringBuilder report, String kind, Connection observer, String sql,
                                       long blockerConnection, Long rowId) {
        try (var query = observer.prepareStatement(sql)) {
            query.setQueryTimeout(1);
            query.setString(1, rowId.toString());
            query.setString(2, MYSQL.getDatabaseName());
            query.setLong(3, blockerConnection);
            int count = 0;
            try (var rows = query.executeQuery()) {
                while (count < 16 && rows.next()) {
                    count++;
                    report.append(kind).append('#').append(count).append(" connection=")
                            .append(rows.getLong("connection_id"));
                    if (rows.wasNull()) {
                        report.append("(UNKNOWN)");
                    }
                    for (String column : List.of("OBJECT_NAME", "INDEX_NAME", "LOCK_TYPE", "LOCK_MODE", "LOCK_STATUS")) {
                        report.append(' ').append(column).append('=')
                                .append(RowWaitDiagnostics.label(rows.getString(column)));
                    }
                    report.append(" dataClass=").append(RowWaitDiagnostics.dataClass(rows.getString("data_class")))
                            .append('\n');
                }
            }
            report.append(kind).append("Rows=").append(count).append(" limit=16\n");
        } catch (Throwable diagnosticFailure) {
            // 診断の例外本文・cause は SQL や接続情報を含み得るので出さない。
            report.append(kind).append("Unavailable=").append(diagnosticFailure.getClass().getSimpleName()).append('\n');
        }
    }

    /** 外側クラスや基底の Testcontainers を初期化せず、出力契約だけを単体検証できる。 */
    static final class RowWaitDiagnostics {
        private static final int MAX_BYTES = 8192;

        private RowWaitDiagnostics() { }

        static AssertionError preserveFailure(AssertionError failure, Supplier<String> collect, Consumer<String> output) {
            String report;
            try {
                report = collect.get();
            } catch (Throwable diagnosticFailure) {
                report = "[invite-row-wait-timeout] unavailable=" + diagnosticFailure.getClass().getSimpleName();
            }
            try {
                output.accept(bounded(report));
            } catch (Throwable outputFailure) {
                // 出力先の障害でも元の assertion を置換しない。診断で成功に戻すこともない。
                return failure;
            }
            return failure;
        }

        static String dataClass(String value) {
            return switch (value == null ? "" : value) {
                case "NULL", "PRIMARY_TARGET", "PRIMARY_OTHER", "NONPRIMARY_REDACTED" -> value;
                default -> "UNKNOWN";
            };
        }

        static String label(String value) {
            return value == null ? "NULL" : value.matches("[A-Za-z0-9_$, ]{1,64}") ? value : "REDACTED";
        }

        static String bounded(String report) {
            // UTF-8 の途中で切らない。println の改行分を含めても 8KiB を超えない。
            if (report.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES - 2) {
                return report;
            }
            String truncated = "\n[TRUNCATED]";
            int budget = MAX_BYTES - 2 - truncated.length();
            int bytes = 0;
            int end = 0;
            while (end < report.length()) {
                int point = report.codePointAt(end);
                int size = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > budget) {
                    break;
                }
                bytes += size;
                end += Character.charCount(point);
            }
            return report.substring(0, end) + truncated;
        }
    }

    protected static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("試練の同期点が期限内に到着しない");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("試練の同期が中断された", e);
        }
    }

    protected static Throwable outcome(Future<?> future) {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError("worker が期限内に終わらない（デッドロック／待ち続けの疑い）", e);
        }
    }

    protected static boolean chainContains(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 後始末（基底の invite_tokens / users 削除より先に走る）
    // ------------------------------------------------------------------

    @AfterEach
    void removeAcceptanceFixtures() {
        boolean claims = claimsTableExists();
        for (Scope scope : List.copyOf(acceptanceScopes)) {
            String column = "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
            if (claims) {
                jdbc.update("DELETE c FROM " + CLAIMS + " c JOIN invite_tokens t ON t.id = c.token_id WHERE t." + column + " = ?",
                        scope.id());
            }
            if ("TEAM".equals(scope.type())) {
                jdbc.update("DELETE FROM team_blocks WHERE team_id = ?", scope.id());
            } else {
                jdbc.update("DELETE FROM organization_blocks WHERE organization_id = ?", scope.id());
            }
        }
        for (Long userId : List.copyOf(acceptanceUsers)) {
            jdbc.update("DELETE FROM audit_logs WHERE user_id = ? OR target_user_id = ?", userId, userId);
            jdbc.update("DELETE FROM team_blocks WHERE user_id = ? OR blocked_by = ?", userId, userId);
            jdbc.update("DELETE FROM organization_blocks WHERE user_id = ? OR blocked_by = ?", userId, userId);
            jdbc.update("DELETE FROM join_requests WHERE requester_user_id = ? OR reviewer_user_id = ?", userId, userId);
            jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
        }
        acceptanceScopes.clear();
        acceptanceUsers.clear();
    }
}
