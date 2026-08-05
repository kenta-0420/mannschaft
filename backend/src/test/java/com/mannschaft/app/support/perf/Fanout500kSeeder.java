package com.mannschaft.app.support.perf;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CMP-001 通知 fan-out「50万人規模」負荷試験ハーネス用の合成データ投入ヘルパー。
 *
 * <p>{@link Fanout10kSeeder}（Hibernate {@code persist} 方式）は1万件規模では十分だが、
 * 50万件では 1 次キャッシュ・dirty checking のオーバーヘッドで約 32 分かかり実用にならない。
 * 本ヘルパーは <b>JDBC {@code PreparedStatement.addBatch()} + {@code executeBatch()}</b> で
 * Hibernate を完全に迂回し、1 組織直属（{@code user_roles.organization_id}）の
 * 50万 ACTIVE メンバーを数十秒〜数分で投入する。</p>
 *
 * <h2>対象スコープの選定（ORGANIZATION 直属 vs team_org_memberships 経由）</h2>
 * <p>実配信経路 {@code OrgFanoutRecipientSource} が叩く
 * {@code UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset} は
 * {@code user_roles.organization_id IN org_tree}（組織直属）<b>OR</b>
 * {@code user_roles.team_id IN (team_org_memberships 経由の配下 ACTIVE チーム)} の和集合で母集団を解決する。
 * 本ハーネスは前者（組織直属＝{@code user_roles.organization_id}）のみで 50万件を構成する。
 * 理由: 50万件をチーム経由で作るには追加で teams・team_org_memberships の投入が必要になり、
 * クエリ上は同じ WHERE 句の OR 分岐を通るだけで測定対象（キーセットページング・チャンク配信・
 * カーソル耐久化のスループット）に本質的な差はない。テーブル数を絞ることで投入時間そのものを
 * 支配的コストにしない（AC-1 は「組織直属50万 ACTIVE を投入し全 NOT NULL 列を充填する」の要件を満たす）。</p>
 *
 * <h2>NOT NULL 列の充填</h2>
 * <p>{@code application-test.yml} は {@code ddl-auto=create}（Entity 由来スキーマ・Flyway 無効）のため
 * デフォルト値・シードは効かない。{@code users} の NOT NULL 列は
 * {@code OrgFanoutRecipientSourceRedIT#insertUser}（実測済みの列集合）と同一を踏襲する。
 * {@code user_roles} は {@code role_id} が NOT NULL（クロスドメイン FK 無しのため {@code roles} 表の
 * 実在チェックは掛からない・原則1）。</p>
 *
 * <h2>ID 帯の隔離</h2>
 * <p>他テストとの衝突回避のため高位レンジ {@link #USER_ID_BASE}（7億台）を使う
 * （{@code Fanout10kSeeder} は 9億台、{@code OrgFanoutRecipientSourceRedIT} は最大でも 1000万未満）。</p>
 */
public final class Fanout500kSeeder {

    /** 既定の投入規模（50万人）。 */
    public static final int DEFAULT_MEMBER_COUNT = 500_000;

    /** users.id / user_roles.user_id の開始値（他テストとの衝突回避のため高位レンジ）。 */
    public static final long USER_ID_BASE = 700_000_000L;

    /** 1 バッチあたりの JDBC addBatch 件数。 */
    private static final int BATCH_SIZE = 2_000;

    /** ORG 直属メンバーに割り当てる user_roles.role_id（クロスドメイン FK 無しのため roles 表実在は不問）。 */
    private static final long ROLE_ID = 3L;

    private static final AtomicBoolean SEEDED = new AtomicBoolean(false);
    private static volatile SeedResult cachedResult;

    private final JdbcTemplate jdbc;

    public Fanout500kSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 1 JVM 内で 1 度だけ既定規模（50万）を投入する（複数 IT クラスからの重複 seed を避ける）。
     */
    public SeedResult seedOnce() {
        return seedOnce(DEFAULT_MEMBER_COUNT);
    }

    /**
     * 1 JVM 内で 1 度だけ {@code memberCount} 件を投入する。2 回目以降の呼び出しはキャッシュ結果を返す
     * （規模の異なる 2 回目の呼び出しは無視される点に注意。縮小 smoke と本番規模は別 JVM 実行を想定）。
     */
    public SeedResult seedOnce(int memberCount) {
        if (SEEDED.compareAndSet(false, true)) {
            cachedResult = seed(memberCount);
        }
        return cachedResult;
    }

    /**
     * 1 組織＋{@code memberCount} 件の ACTIVE 直属メンバー（users + user_roles）を投入する。
     * 呼び出しごとに毎回投入する（重複除けが不要なテストから直接呼ぶ場合に使う）。
     *
     * @param memberCount 投入する ACTIVE メンバー数
     * @return 投入結果（組織 ID・user_id 範囲）
     */
    public SeedResult seed(int memberCount) {
        long t0 = System.nanoTime();
        long organizationId = insertOrganization();
        long userIdFrom = USER_ID_BASE;
        insertUsersBatch(userIdFrom, memberCount);
        insertUserRolesBatch(userIdFrom, memberCount, organizationId);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new SeedResult(organizationId, userIdFrom, memberCount, ms);
    }

    private long insertOrganization() {
        String slug = "perf-fanout-500k-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO organizations "
                        + "(name, org_type, visibility, hierarchy_visibility, supporter_enabled, version, slug, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'OTHER', 'PUBLIC', 'NONE', 1, 0, ?, NOW(), NOW())",
                "CMP-001 fan-out 500k 実測組織", slug);
        Long id = jdbc.queryForObject("SELECT id FROM organizations WHERE slug = ?", Long.class, slug);
        if (id == null) {
            throw new IllegalStateException("組織投入に失敗した（slug=" + slug + "）");
        }
        return id;
    }

    /**
     * {@code users} を JDBC バッチ INSERT する。NOT NULL 列は全て明示充填する
     * （列漏れは error 1364 でバッチ全体が落ちるため注意）。
     */
    private void insertUsersBatch(long userIdFrom, int count) {
        String sql = "INSERT INTO users ("
                + "id, email, last_name, first_name, display_name, status, deleted_at, created_at, updated_at, "
                + "handle_searchable, contact_approval_required, online_visibility, is_searchable, dm_receive_from, "
                + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only"
                + ") VALUES ("
                + "?, ?, 'L', 'F', ?, 'ACTIVE', NULL, ?, ?, "
                + "1, 1, 'NOBODY', 1, 'ANYONE', "
                + "1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                + "1, 0)";
        LocalDateTime now = LocalDateTime.now();
        batchExecute(sql, count, (ps, i) -> {
            long userId = userIdFrom + i;
            ps.setLong(1, userId);
            ps.setString(2, "fanout500k-" + userId + "@example.test");
            ps.setString(3, "U" + userId);
            ps.setObject(4, now);
            ps.setObject(5, now);
        });
    }

    /**
     * {@code user_roles} を JDBC バッチ INSERT する（組織直属＝{@code organization_id} 指定・
     * {@code team_id} は NULL）。{@code scope_key} は生成列（insertable=false）のため列挙しない。
     */
    private void insertUserRolesBatch(long userIdFrom, int count, long organizationId) {
        String sql = "INSERT INTO user_roles "
                + "(user_id, role_id, team_id, organization_id, created_at, updated_at) "
                + "VALUES (?, ?, NULL, ?, ?, ?)";
        LocalDateTime now = LocalDateTime.now();
        batchExecute(sql, count, (ps, i) -> {
            long userId = userIdFrom + i;
            ps.setLong(1, userId);
            ps.setLong(2, ROLE_ID);
            ps.setLong(3, organizationId);
            ps.setObject(4, now);
            ps.setObject(5, now);
        });
    }

    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement ps, int rowIndex) throws SQLException;
    }

    /** {@code count} 行を {@link #BATCH_SIZE} 件ずつ {@code addBatch}/{@code executeBatch} する。 */
    private void batchExecute(String sql, int count, RowBinder binder) {
        jdbc.execute((java.sql.Connection con) -> {
            boolean prevAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                int inBatch = 0;
                for (int i = 0; i < count; i++) {
                    binder.bind(ps, i);
                    ps.addBatch();
                    inBatch++;
                    if (inBatch == BATCH_SIZE) {
                        ps.executeBatch();
                        con.commit();
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    ps.executeBatch();
                    con.commit();
                }
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(prevAutoCommit);
            }
            return null;
        });
    }

    /** 投入結果。 */
    public record SeedResult(long organizationId, long userIdFrom, int memberCount, long seedMs) {
        public long userIdTo() {
            return userIdFrom + memberCount - 1;
        }
    }
}
