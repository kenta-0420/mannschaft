package com.mannschaft.app.common.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>クロスドメインFK撤廃 最終局面 5-A（Phase 5-A・第一弾）の番人テスト。</b>
 *
 * <p>V114.001 で「参照先が論理削除のみで物理削除されない＝CASCADE/RESTRICT が発火し得ない『発火不能群』12件」を
 * 撤廃only する（CASCADE 7件＋RESTRICT→organizations 4件＋RESTRICT→teams 1件）。参照先テーブルは
 * timeline_posts / shared_files / committees / projects / todos / organizations / teams の7種で、いずれも
 * 本番では deleted_at による論理削除のみで物理 DELETE 経路を持たない。したがって ON DELETE CASCADE も
 * 既定の ON DELETE RESTRICT も現実には発火し得ず、FK を撤廃しても挙動は一切変わらない。</p>
 *
 * <p>本テストは「もし参照先が（テスト内で意図的に）物理削除されても、参照元の外部キー列が
 * 削除/NULL化されず孤児値を保持し続ける」ことを厳密に検証し、撤廃only（孤児保持）の肝を直接守る:</p>
 * <ol>
 *   <li>V114.001 の直前（origin/main 最大 = V113.001）まで適用 → 参照先行＋参照元行（外部キー列に参照先 id をセット）をシード。</li>
 *   <li>残り（V114.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V114.001 で対象12FKが撤廃される。</li>
 *   <li><b>参照先テーブルの行を（テスト内で）物理 DELETE しても、参照元の外部キー列が孤児値を保持し続ける</b>
 *       （CASCADE による子の連鎖削除も起きず、RESTRICT による親 DELETE 阻止も起きない）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。
 * 物理削除する参照先テーブル（organizations / teams 等）は、当該行を ON DELETE RESTRICT で被参照する
 * 「撤廃対象FK以外の余計な子行」を一切シードしない。撤廃対象12FKの参照元のみをシードし、それらは
 * V114.001 適用後には FK が消えている（または CASCADE）ため、参照先の物理 DELETE を阻害しない。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataUnfireableCrossDomainFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ 発火不能クロスドメインFK撤廃（V114.001・Phase 5-A）番人テスト")
class FlywayExistingDataUnfireableCrossDomainFkMigrationTest {

    /** V114.001 の直前の版（origin/main 最大 = V113.001）。ここまで適用して参照元/先をシードする。 */
    private static final String PRE_V114_001_TARGET = "113.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase5a_unfireable_fk")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void migrateToPreTarget() {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V114_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V113.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V114.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /**
     * 1回の pre→seed→migrate サイクルで12件すべてを検証する。
     *
     * <p>注意: 同一DBを共有するため複数 @Test に分けると、2本目以降は既に V114.001 まで適用済みとなり
     * 「FK実在 sanity（pre-state）」が成立しなくなる。よって1メソッドに集約し、
     * V113.001 時点での全12FK実在 → 12件ぶんのシード → 残り適用 → 全12FK撤廃 + 12件の孤児保持
     * を一気通貫で検証する。</p>
     */
    @Test
    @DisplayName("V113.001で全12FK実在_V114.001適用で全12FK撤廃_参照先物理削除でも参照元の外部キー列が孤児値を保持")
    void 既存データを持つDBでV114_001が発火不能クロスドメインFK12件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        // 参照元（referencing source）id ＋ 撤廃対象 FK 列にセットする値
        final long fcfId;                 // friend_content_forwards.{source_post_id, forwarded_post_id}
        final long sourcePostId;          // → timeline_posts（物理削除対象）
        final long forwardedPostId;       // → timeline_posts（物理削除対象）
        final long pwdId;                 // property_work_documents.shared_file_id
        final long sharedFileId;          // → shared_files（物理削除対象）
        final long scheduleId;            // schedules.committee_id
        final long committeeId;           // → committees（物理削除対象）
        final long tblProjectId;          // todo_budget_links（project 紐付行）.project_id
        final long projectId;             // → projects（物理削除対象）
        final long tblTodoId;             // todo_budget_links（todo 紐付行）.todo_id
        final long todoForTblId;          // → todos（物理削除対象・tbl 用）
        final long ttlId;                 // todo_tag_links.todo_id
        final long todoForTtlId;          // → todos（物理削除対象・ttl 用）
        final long ncpId;                 // notification_credit_purchases.organization_id
        final long nmuId;                 // notification_monthly_usage.organization_id
        final long oemId;                 // organization_enabled_modules.organization_id
        final long onbId;                 // organization_notification_balances.organization_id
        final long orgOrphan;             // → organizations（物理削除対象・上記4件の参照元のみが参照）
        final long rpId;                  // recruitment_participants.team_id
        final long teamOrphan;            // → teams（物理削除対象・recruitment_participants のみが参照）

        try (Connection c = conn()) {
            // ── given: V113.001 時点では対象12FKが全て実在すること（pre-state sanity）──
            assertThat(foreignKeyExists(c, "friend_content_forwards", "fk_fcf_forwarded_post"))
                    .as("V113.001 時点では fk_fcf_forwarded_post が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "friend_content_forwards", "fk_fcf_source_post"))
                    .as("V113.001 時点では fk_fcf_source_post が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "property_work_documents", "fk_pwd_file"))
                    .as("V113.001 時点では fk_pwd_file が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "schedules", "fk_schedules_committee"))
                    .as("V113.001 時点では fk_schedules_committee が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "todo_budget_links", "fk_tbl_project"))
                    .as("V113.001 時点では fk_tbl_project が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "todo_budget_links", "fk_tbl_todo"))
                    .as("V113.001 時点では fk_tbl_todo が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "todo_tag_links", "fk_todo_tag_links_todo"))
                    .as("V113.001 時点では fk_todo_tag_links_todo が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "notification_credit_purchases", "fk_ncp_org"))
                    .as("V113.001 時点では fk_ncp_org が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "notification_monthly_usage", "fk_nmu_org"))
                    .as("V113.001 時点では fk_nmu_org が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "organization_enabled_modules", "fk_oem_org"))
                    .as("V113.001 時点では fk_oem_org が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "organization_notification_balances", "fk_onb_org"))
                    .as("V113.001 時点では fk_onb_org が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "recruitment_participants", "fk_rp_team"))
                    .as("V113.001 時点では fk_rp_team が実在すること").isTrue();

            // ── 共通の親 ──
            long actorUserId = insertUser(c, "p5a-actor@example.com");
            // organizations は2系統に分離する:
            //   - orgParent : committees / shared_folders / notification系の親として使う（物理削除しない）
            //   - orgOrphan : org参照4FKの孤児検証専用（物理削除する。CASCADE/RESTRICT 子をぶら下げない）
            long orgParent = insertOrganization(c, "p5a-org-parent");
            long teamParent = insertTeam(c, "p5a-team-parent"); // 各種チームスコープ親（物理削除しない）

            // ── 1+2. friend_content_forwards → timeline_posts ×2（source / forwarded）──
            // timeline_posts は user_id(CASCADE) NOT NULL。scope_id/posted_as_type/status は DEFAULT あり。
            sourcePostId = insertTimelinePost(c, actorUserId, "TEAM", teamParent);
            forwardedPostId = insertTimelinePost(c, actorUserId, "TEAM", teamParent);
            // friend_content_forwards: source_post_id/source_team_id/forwarding_team_id/forwarded_post_id/target/forwarded_by NOT NULL。
            fcfId = insertFriendContentForward(c, sourcePostId, teamParent, teamParent, forwardedPostId, actorUserId);

            // ── 3. property_work_documents → shared_files ──
            // shared_files: folder_id(CASCADE) NOT NULL → shared_folders。name/file_key/file_size/content_type/created_at/updated_at NOT NULL。
            long sharedFolderId = insertSharedFolder(c, "ORGANIZATION", orgParent, "org", actorUserId);
            sharedFileId = insertSharedFile(c, sharedFolderId);
            // property_work_documents: package_id(CASCADE)/shared_file_id(撤廃対象)/document_kind/created_by/created_at NOT NULL。
            long pwpId = insertPropertyWorkPackage(c, "ORGANIZATION", orgParent, actorUserId);
            pwdId = insertPropertyWorkDocument(c, pwpId, sharedFileId, actorUserId);

            // ── 4. schedules → committees（committee スコープでシード）──
            // committees: organization_id(CASCADE)/name NOT NULL。残りは DEFAULT あり。
            committeeId = insertCommittee(c, orgParent);
            // schedules: title/start_at NOT NULL ＋ ck_schedules_scope_xor（team/org/user/committee の1つのみ）→ committee_id をセット。
            scheduleId = insertScheduleWithCommittee(c, committeeId);

            // ── 5+6. todo_budget_links → projects / todos（XOR なので別行で2本）──
            // budget chain: budget_fiscal_years(RESTRICT)→budget_categories(RESTRICT)→shift_budget_allocations(allocation_id RESTRICT)。
            long fiscalYearId = insertBudgetFiscalYear(c, "ORGANIZATION", orgParent, actorUserId);
            long budgetCategoryId = insertBudgetCategory(c, fiscalYearId);
            long allocationId = insertShiftBudgetAllocation(c, orgParent, fiscalYearId, budgetCategoryId, actorUserId);
            projectId = insertProject(c, "ORGANIZATION", orgParent, actorUserId);
            todoForTblId = insertTodo(c, "ORGANIZATION", orgParent, actorUserId);
            // project 紐付行（project_id NOT NULL / todo_id NULL）。
            tblProjectId = insertTodoBudgetLink(c, projectId, null, allocationId, actorUserId);
            // todo 紐付行（project_id NULL / todo_id NOT NULL）。
            tblTodoId = insertTodoBudgetLink(c, null, todoForTblId, allocationId, actorUserId);

            // ── 7. todo_tag_links → todos ──
            // tags: scope_type/scope_id/name/created_by NOT NULL。
            long tagId = insertTag(c, "ORGANIZATION", orgParent, actorUserId);
            todoForTtlId = insertTodo(c, "ORGANIZATION", orgParent, actorUserId);
            ttlId = insertTodoTagLink(c, todoForTtlId, tagId);

            // ── 8〜11. organizations を参照する4FK（孤児検証専用 org に集約）──
            orgOrphan = insertOrganization(c, "p5a-org-orphan");
            // notification_credit_packages はマスタ seed 済（V9.118）→ 既存 id=1 を流用。
            ncpId = insertNotificationCreditPurchase(c, orgOrphan, 1L, actorUserId);
            nmuId = insertNotificationMonthlyUsage(c, orgOrphan);
            // module_definitions は seed が無い場合があるため明示挿入。
            long moduleId = insertModuleDefinition(c, "p5a-mod");
            oemId = insertOrganizationEnabledModule(c, orgOrphan, moduleId, actorUserId);
            onbId = insertOrganizationNotificationBalance(c, orgOrphan);

            // ── 12. recruitment_participants → teams（TEAM 参加者でシード）──
            teamOrphan = insertTeam(c, "p5a-team-orphan");
            // recruitment_listings: scope_type/scope_id/category_id/title/participation_type/start_at/end_at/
            //   application_deadline/auto_cancel_at/capacity/min_capacity/created_by NOT NULL ＋ 各種 CHECK。
            // recruitment_categories は seed 済（V3.119）→ TEAM 系 'tournament' を使うため code 検索で id 取得。
            long categoryId = lookupRecruitmentCategoryId(c, "tournament");
            long listingId = insertRecruitmentListing(c, "ORGANIZATION", orgParent, categoryId, actorUserId);
            // participant_type='TEAM' ＋ team_id NOT NULL（chk_rp_subject）→ team_id（撤廃対象列）をセット。
            rpId = insertRecruitmentParticipantTeam(c, listingId, teamOrphan);
        }

        // ── when: 残り（V114.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象12FKが全て撤廃された ──
            assertThat(foreignKeyExists(c, "friend_content_forwards", "fk_fcf_forwarded_post"))
                    .as("V114.001 で fk_fcf_forwarded_post が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "friend_content_forwards", "fk_fcf_source_post"))
                    .as("V114.001 で fk_fcf_source_post が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "property_work_documents", "fk_pwd_file"))
                    .as("V114.001 で fk_pwd_file が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "schedules", "fk_schedules_committee"))
                    .as("V114.001 で fk_schedules_committee が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "todo_budget_links", "fk_tbl_project"))
                    .as("V114.001 で fk_tbl_project が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "todo_budget_links", "fk_tbl_todo"))
                    .as("V114.001 で fk_tbl_todo が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "todo_tag_links", "fk_todo_tag_links_todo"))
                    .as("V114.001 で fk_todo_tag_links_todo が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "notification_credit_purchases", "fk_ncp_org"))
                    .as("V114.001 で fk_ncp_org が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "notification_monthly_usage", "fk_nmu_org"))
                    .as("V114.001 で fk_nmu_org が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "organization_enabled_modules", "fk_oem_org"))
                    .as("V114.001 で fk_oem_org が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "organization_notification_balances", "fk_onb_org"))
                    .as("V114.001 で fk_onb_org が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "recruitment_participants", "fk_rp_team"))
                    .as("V114.001 で fk_rp_team が撤廃されること").isFalse();

            // ── then-2（中核）: 各参照先テーブルの行を物理削除しても、参照元の外部キー列が孤児値を保持 ──

            // 1. timeline_posts(forwarded) 物理削除 → friend_content_forwards.forwarded_post_id 孤児保持
            deleteRow(c, "timeline_posts", forwardedPostId);
            assertThat(rowExists(c, "timeline_posts", forwardedPostId))
                    .as("参照先 timeline_post(forwarded) が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "friend_content_forwards", fcfId))
                    .as("forwarded_post 物理削除でも friend_content_forwards が CASCADE 削除されず生存すること").isTrue();
            assertThat(longColumn(c, "friend_content_forwards", "forwarded_post_id", fcfId))
                    .as("friend_content_forwards.forwarded_post_id が孤児値を保持すること").isEqualTo(forwardedPostId);

            // 2. timeline_posts(source) 物理削除 → friend_content_forwards.source_post_id 孤児保持
            deleteRow(c, "timeline_posts", sourcePostId);
            assertThat(rowExists(c, "timeline_posts", sourcePostId))
                    .as("参照先 timeline_post(source) が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "friend_content_forwards", "source_post_id", fcfId))
                    .as("friend_content_forwards.source_post_id が孤児値を保持すること").isEqualTo(sourcePostId);

            // 3. shared_files 物理削除 → property_work_documents.shared_file_id 孤児保持
            deleteRow(c, "shared_files", sharedFileId);
            assertThat(rowExists(c, "shared_files", sharedFileId))
                    .as("参照先 shared_file が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "property_work_documents", pwdId))
                    .as("shared_file 物理削除でも property_work_documents が CASCADE 削除されず生存すること").isTrue();
            assertThat(longColumn(c, "property_work_documents", "shared_file_id", pwdId))
                    .as("property_work_documents.shared_file_id が孤児値を保持すること").isEqualTo(sharedFileId);

            // 4. committees 物理削除 → schedules.committee_id 孤児保持
            deleteRow(c, "committees", committeeId);
            assertThat(rowExists(c, "committees", committeeId))
                    .as("参照先 committee が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "schedules", scheduleId))
                    .as("committee 物理削除でも schedules が CASCADE 削除されず生存すること").isTrue();
            assertThat(longColumn(c, "schedules", "committee_id", scheduleId))
                    .as("schedules.committee_id が孤児値を保持すること").isEqualTo(committeeId);

            // 5. projects 物理削除 → todo_budget_links.project_id 孤児保持
            deleteRow(c, "projects", projectId);
            assertThat(rowExists(c, "projects", projectId))
                    .as("参照先 project が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "todo_budget_links", tblProjectId))
                    .as("project 物理削除でも todo_budget_links(project) が CASCADE 削除されず生存すること").isTrue();
            assertThat(longColumn(c, "todo_budget_links", "project_id", tblProjectId))
                    .as("todo_budget_links.project_id が孤児値を保持すること").isEqualTo(projectId);

            // 6. todos 物理削除 → todo_budget_links.todo_id 孤児保持
            deleteRow(c, "todos", todoForTblId);
            assertThat(rowExists(c, "todos", todoForTblId))
                    .as("参照先 todo(tbl) が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "todo_budget_links", tblTodoId))
                    .as("todo 物理削除でも todo_budget_links(todo) が CASCADE 削除されず生存すること").isTrue();
            assertThat(longColumn(c, "todo_budget_links", "todo_id", tblTodoId))
                    .as("todo_budget_links.todo_id が孤児値を保持すること").isEqualTo(todoForTblId);

            // 7. todos 物理削除 → todo_tag_links.todo_id 孤児保持
            deleteRow(c, "todos", todoForTtlId);
            assertThat(rowExists(c, "todos", todoForTtlId))
                    .as("参照先 todo(ttl) が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "todo_tag_links", ttlId))
                    .as("todo 物理削除でも todo_tag_links が CASCADE 削除されず生存すること").isTrue();
            assertThat(longColumn(c, "todo_tag_links", "todo_id", ttlId))
                    .as("todo_tag_links.todo_id が孤児値を保持すること").isEqualTo(todoForTtlId);

            // 8〜11. organizations(orphan) 物理削除 → org参照4FKの外部キー列が孤児保持
            //   （4件の参照元のみが orgOrphan を参照しており、撤廃後は FK が消えているため RESTRICT に阻まれない）
            deleteRow(c, "organizations", orgOrphan);
            assertThat(rowExists(c, "organizations", orgOrphan))
                    .as("参照先 organization(orphan) が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "notification_credit_purchases", "organization_id", ncpId))
                    .as("notification_credit_purchases.organization_id が孤児値を保持すること").isEqualTo(orgOrphan);
            assertThat(longColumn(c, "notification_monthly_usage", "organization_id", nmuId))
                    .as("notification_monthly_usage.organization_id が孤児値を保持すること").isEqualTo(orgOrphan);
            assertThat(longColumn(c, "organization_enabled_modules", "organization_id", oemId))
                    .as("organization_enabled_modules.organization_id が孤児値を保持すること").isEqualTo(orgOrphan);
            assertThat(longColumn(c, "organization_notification_balances", "organization_id", onbId))
                    .as("organization_notification_balances.organization_id が孤児値を保持すること").isEqualTo(orgOrphan);

            // 12. teams(orphan) 物理削除 → recruitment_participants.team_id 孤児保持
            //   （recruitment_participants のみが teamOrphan を参照しており、撤廃後は FK が消えているため RESTRICT に阻まれない）
            deleteRow(c, "teams", teamOrphan);
            assertThat(rowExists(c, "teams", teamOrphan))
                    .as("参照先 team(orphan) が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "recruitment_participants", rpId))
                    .as("team 物理削除でも recruitment_participants が生存すること").isTrue();
            assertThat(longColumn(c, "recruitment_participants", "team_id", rpId))
                    .as("recruitment_participants.team_id が孤児値を保持すること").isEqualTo(teamOrphan);
        }
    }

    // ── 共通 seed helpers ──────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '最終', '5A郎', '最終5A郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** organizations 行を挿入。slug NOT NULL+UNIQUE（3〜30英数ハイフン）/ org_type は VARCHAR(30)（'OTHER'）。 */
    private long insertOrganization(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organizations
                    (name, org_type, slug, created_at, updated_at)
                VALUES ('最終局面5A監査組織', 'OTHER', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** teams 行を挿入。name NOT NULL / slug NOT NULL+UNIQUE / chk_teams_visibility（'PUBLIC'）。 */
    private long insertTeam(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams
                    (name, slug, visibility, created_at, updated_at)
                VALUES ('最終局面5Aチーム', ?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 1+2. timeline_posts / friend_content_forwards ──

    /** timeline_posts 行を挿入（参照先）。user_id(CASCADE) NOT NULL。scope_id/posted_as_type/status は DEFAULT あり。 */
    private long insertTimelinePost(Connection c, long userId, String scopeType, long scopeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timeline_posts
                    (scope_type, scope_id, user_id, content)
                VALUES (?, ?, ?, '最終局面5A投稿')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * friend_content_forwards 行を挿入。
     * source_post_id/source_team_id/forwarding_team_id/forwarded_post_id/target/forwarded_by NOT NULL。
     * UNIQUE uq_fcf_active(source_post_id, forwarding_team_id, is_revoked)。
     */
    private long insertFriendContentForward(Connection c, long sourcePostId, long sourceTeamId,
                                            long forwardingTeamId, long forwardedPostId, long forwardedBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO friend_content_forwards
                    (source_post_id, source_team_id, forwarding_team_id, forwarded_post_id, target, forwarded_by)
                VALUES (?, ?, ?, ?, 'MEMBER', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, sourcePostId);
            ps.setLong(2, sourceTeamId);
            ps.setLong(3, forwardingTeamId);
            ps.setLong(4, forwardedPostId);
            ps.setLong(5, forwardedBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 3. shared_folders / shared_files / property_work_packages / property_work_documents ──

    /** shared_folders 行を挿入（shared_files.folder_id CASCADE の親）。scope_type/name NOT NULL ＋ created_at/updated_at NOT NULL。 */
    private long insertSharedFolder(Connection c, String scopeType, long orgId, String scopeColumn, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_folders
                    (scope_type, organization_id, name, created_by, created_at, updated_at)
                VALUES (?, ?, '最終局面5Aフォルダ', ?, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, orgId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** shared_files 行を挿入（参照先）。folder_id(CASCADE)/name/file_key/file_size/content_type/created_at/updated_at NOT NULL。 */
    private long insertSharedFile(Connection c, long folderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_files
                    (folder_id, name, file_key, file_size, content_type, created_at, updated_at)
                VALUES (?, '最終局面5A.pdf', 'p5a/key/file.pdf', 1024, 'application/pdf', NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, folderId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** property_work_packages 行を挿入（property_work_documents.package_id CASCADE の親）。scope/work_type/title/created_by/created_at/updated_at NOT NULL ＋ CHECK。 */
    private long insertPropertyWorkPackage(Connection c, String scopeType, long scopeId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO property_work_packages
                    (scope_type, scope_id, work_type, title, created_by, created_at, updated_at)
                VALUES (?, ?, 'REPAIR', '最終局面5A工事', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** property_work_documents 行を挿入。package_id(CASCADE)/shared_file_id(撤廃対象)/document_kind/created_by/created_at NOT NULL ＋ chk_pwd_kind。 */
    private long insertPropertyWorkDocument(Connection c, long packageId, long sharedFileId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO property_work_documents
                    (package_id, shared_file_id, document_kind, created_by, created_at)
                VALUES (?, ?, 'REPORT', ?, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, packageId);
            ps.setLong(2, sharedFileId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 4. committees / schedules ──

    /** committees 行を挿入（参照先）。organization_id(CASCADE)/name NOT NULL。残りは DEFAULT あり。 */
    private long insertCommittee(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO committees (organization_id, name)
                VALUES (?, '最終局面5A委員会')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** schedules 行を挿入。title/start_at NOT NULL ＋ ck_schedules_scope_xor → committee_id のみセット（撤廃対象列）。 */
    private long insertScheduleWithCommittee(Connection c, long committeeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules (committee_id, title, start_at)
                VALUES (?, '最終局面5A予定', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, committeeId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 5+6. budget chain / projects / todos / todo_budget_links ──

    /** budget_fiscal_years 行を挿入（budget_categories / shift_budget_allocations の祖先）。scope/name/start_date/end_date/created_by NOT NULL ＋ CHECK(start<end)。 */
    private long insertBudgetFiscalYear(Connection c, String scopeType, long scopeId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_fiscal_years
                    (scope_type, scope_id, name, start_date, end_date, created_by)
                VALUES (?, ?, '最終局面5A年度', '2026-04-01', '2027-03-31', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** budget_categories 行を挿入（shift_budget_allocations.budget_category_id RESTRICT の親）。fiscal_year_id/name/category_type NOT NULL ＋ CHECK。 */
    private long insertBudgetCategory(Connection c, long fiscalYearId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_categories (fiscal_year_id, name, category_type)
                VALUES (?, '最終局面5A費目', 'EXPENSE')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fiscalYearId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * shift_budget_allocations 行を挿入（todo_budget_links.allocation_id RESTRICT の親）。
     * organization_id(CASCADE)/fiscal_year_id(RESTRICT)/budget_category_id(RESTRICT)/period_start/period_end/
     * allocated_amount/created_by NOT NULL ＋ CHECK(amount>=0 / period_start<=period_end)。
     */
    private long insertShiftBudgetAllocation(Connection c, long organizationId, long fiscalYearId,
                                             long budgetCategoryId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_budget_allocations
                    (organization_id, fiscal_year_id, budget_category_id, period_start, period_end,
                     allocated_amount, created_by)
                VALUES (?, ?, ?, '2026-04-01', '2026-04-30', 100000, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, fiscalYearId);
            ps.setLong(3, budgetCategoryId);
            ps.setLong(4, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** projects 行を挿入（参照先）。scope_type/scope_id/title/created_by NOT NULL。status/visibility は DEFAULT あり。 */
    private long insertProject(Connection c, String scopeType, long scopeId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO projects (scope_type, scope_id, title, created_by)
                VALUES (?, ?, '最終局面5Aプロジェクト', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** todos 行を挿入（参照先）。scope_type/scope_id/title/created_by NOT NULL。status/priority は DEFAULT あり。 */
    private long insertTodo(Connection c, String scopeType, long scopeId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todos (scope_type, scope_id, title, created_by)
                VALUES (?, ?, '最終局面5A TODO', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * todo_budget_links 行を挿入。allocation_id(RESTRICT)/created_by(RESTRICT) NOT NULL。
     * chk_tbl_target_xor: project_id XOR todo_id（どちらか一方のみ NOT NULL）。currency は DEFAULT 'JPY'。
     */
    private long insertTodoBudgetLink(Connection c, Long projectId, Long todoId, long allocationId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todo_budget_links (project_id, todo_id, allocation_id, created_by)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            if (projectId == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, projectId);
            }
            if (todoId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, todoId);
            }
            ps.setLong(3, allocationId);
            ps.setLong(4, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 7. tags / todo_tag_links ──

    /** tags 行を挿入（todo_tag_links.tag_id CASCADE の親）。scope_type/scope_id/name/created_by NOT NULL。 */
    private long insertTag(Connection c, String scopeType, long scopeId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tags (scope_type, scope_id, name, created_by)
                VALUES (?, ?, '最終局面5Aタグ', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** todo_tag_links 行を挿入。todo_id(撤廃対象 CASCADE)/tag_id(CASCADE) NOT NULL ＋ UNIQUE。created_at は DEFAULT あり。 */
    private long insertTodoTagLink(Connection c, long todoId, long tagId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todo_tag_links (todo_id, tag_id)
                VALUES (?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, todoId);
            ps.setLong(2, tagId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 8〜11. notification 系 / module_definitions ──

    /**
     * notification_credit_purchases 行を挿入。organization_id(撤廃対象)/package_id(CASCADE)/purchased_by_user_id/
     * credits_granted/remaining_credits/price_jpy NOT NULL。payment_status は DEFAULT 'PENDING'。
     */
    private long insertNotificationCreditPurchase(Connection c, long organizationId, long packageId, long purchasedBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO notification_credit_purchases
                    (organization_id, package_id, purchased_by_user_id, credits_granted, remaining_credits, price_jpy)
                VALUES (?, ?, ?, 100000, 100000, 100000)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, packageId);
            ps.setLong(3, purchasedBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** notification_monthly_usage 行を挿入。organization_id(撤廃対象)/month/source_type NOT NULL ＋ UNIQUE uq_nmu。 */
    private long insertNotificationMonthlyUsage(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO notification_monthly_usage
                    (organization_id, month, source_type)
                VALUES (?, '2026-06-01', 'NOTIFY_ALL')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** module_definitions 行を挿入（organization_enabled_modules.module_id RESTRICT の親）。name/slug/module_type/module_number/created_at/updated_at NOT NULL ＋ chk_module_type。 */
    private long insertModuleDefinition(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO module_definitions
                    (name, slug, module_type, module_number, created_at, updated_at)
                VALUES ('最終局面5Aモジュール', ?, 'OPTIONAL', 9001, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** organization_enabled_modules 行を挿入。organization_id(撤廃対象)/module_id(RESTRICT) NOT NULL ＋ UNIQUE。is_enabled は DEFAULT あり。 */
    private long insertOrganizationEnabledModule(Connection c, long organizationId, long moduleId, long enabledBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organization_enabled_modules
                    (organization_id, module_id, is_enabled, enabled_by)
                VALUES (?, ?, 1, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, moduleId);
            ps.setLong(3, enabledBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** organization_notification_balances 行を挿入。organization_id(撤廃対象・UNIQUE)/free_quota_month NOT NULL。 */
    private long insertOrganizationNotificationBalance(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organization_notification_balances
                    (organization_id, free_quota_month)
                VALUES (?, '2026-06-01')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 12. recruitment_categories / recruitment_listings / recruitment_participants ──

    /** recruitment_categories（V3.119 で seed 済）の id を code で引く。 */
    private long lookupRecruitmentCategoryId(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM recruitment_categories WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("recruitment_categories に code=" + code + " が存在すること").isTrue();
                return rs.getLong(1);
            }
        }
    }

    /**
     * recruitment_listings 行を挿入（recruitment_participants.listing_id CASCADE の親）。
     * scope_type/scope_id/category_id/title/participation_type/start_at/end_at/application_deadline/
     * auto_cancel_at/capacity/min_capacity/created_by NOT NULL。
     * CHECK: min_capacity<=capacity / application_deadline<start_at / auto_cancel_at<=application_deadline / start_at<end_at。
     */
    private long insertRecruitmentListing(Connection c, String scopeType, long scopeId, long categoryId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_listings
                    (scope_type, scope_id, category_id, title, participation_type,
                     start_at, end_at, application_deadline, auto_cancel_at, capacity, min_capacity, created_by)
                VALUES (?, ?, ?, '最終局面5A募集', 'TEAM',
                        '2026-07-10 10:00:00', '2026-07-10 12:00:00',
                        '2026-07-08 23:59:59', '2026-07-07 23:59:59', 10, 1, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, categoryId);
            ps.setLong(4, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * recruitment_participants 行を挿入（TEAM 参加者）。listing_id(CASCADE)/participant_type NOT NULL。
     * chk_rp_subject: participant_type='TEAM' なら team_id NOT NULL かつ user_id NULL。team_id（撤廃対象列）をセット。
     */
    private long insertRecruitmentParticipantTeam(Connection c, long listingId, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_participants
                    (listing_id, participant_type, team_id, status)
                VALUES (?, 'TEAM', ?, 'APPLIED')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, listingId);
            ps.setLong(2, teamId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── generic helpers ────────────────────────────────────────

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void deleteRow(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static boolean rowExists(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static long longColumn(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long v = rs.getLong(1);
                return rs.wasNull() ? -1L : v;
            }
        }
    }

    private static boolean foreignKeyExists(Connection c, String table, String constraintName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """)) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
