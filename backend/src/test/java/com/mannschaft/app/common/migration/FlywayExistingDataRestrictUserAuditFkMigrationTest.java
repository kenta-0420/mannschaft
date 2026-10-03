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
 * <b>クロスドメインFK撤廃 最終局面 5-C（Phase 5-C・第三弾＝本丸）の番人テスト。</b>
 *
 * <p>V117.001 で「残った RESTRICT のうち、参照先が users（user ドメイン）である監査/作成者/操作者カラムのFK」25件を
 * 撤廃only する（advertiser/are/cr/coupons/jp/pr/promotions/pl の 8 件 + proxy_input_consents の 5 件 +
 * rcp/rl/rp/rs/reservations/rd/ssp/sba/sbc/tags/tbl/handoff の 12 件 = 25 件）。</p>
 *
 * <p>これらの FK が ON DELETE RESTRICT のままだと、当該 user を参照する子行が1件でも存在すると users の物理削除が
 * MySQL によってブロックされる。退会の物理削除フロー（AccountPurgeService.purgeUser → userRepository.delete(...)）は
 * この RESTRICT に阻まれて退会が滞留し得る潜在バグを抱えていた。本 PR で RESTRICT を撤廃すると、子行は
 * created_by / approved_by / user_id 等に孤児 user_id 値を保持したまま生存し、users の物理 DELETE が貫通する
 * （＝退会 purge ブロック解消・監査履歴温存）。</p>
 *
 * <p>本テストは「参照元の子行をシードし、参照先の users 行を（テスト内で意図的に）物理 DELETE しても、
 * RESTRICT 撤廃済みゆえ users DELETE がブロックされず、子行が孤児 user_id 値を保持し続ける」ことを厳密に検証する:</p>
 * <ol>
 *   <li>V117.001 の直前（origin/main 最大 = V115.001）まで適用 → users 親行＋撤廃対象FKの参照元子行（監査FK列に user id をセット）をシード。</li>
 *   <li>V117.001 直前時点で対象25FKが実在することを sanity 確認。</li>
 *   <li>残り（V117.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V117.001 で対象25FKが全て撤廃される。</li>
 *   <li><b>子行が孤児 user_id を保持したまま、users 親行の物理 DELETE がブロックされずに成功する</b>
 *       （RESTRICT 撤廃only の肝・退会 purge ブロック解消の直接証明）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。
 * 1回の pre→seed→migrate サイクルで25件すべてを検証する（同一DBを共有するため複数 @Test に分けると
 * 2本目以降は既に V117.001 まで適用済みとなり「FK実在 sanity（pre-state）」が成立しないため）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataRestrictUserAuditFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ RESTRICT→users 監査FK撤廃（V117.001・Phase 5-C）番人テスト")
class FlywayExistingDataRestrictUserAuditFkMigrationTest {

    /** V117.001 の直前の版（origin/main 最大 = V115.001）。ここまで適用して参照元/先をシードする。 */
    private static final String PRE_V117_001_TARGET = "115.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase5c_restrict_user_fk")
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
                .target(MigrationVersion.fromVersion(PRE_V117_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V115.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V117.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /** 撤廃対象25FKの (table, constraintName)。pre-state sanity と post-drop 検証に使う。 */
    private static final String[][] TARGET_FKS = {
            {"advertiser_accounts", "fk_advertiser_accounts_approved_by"},
            {"attendance_requirement_evaluations", "fk_are_resolver"},
            {"coupon_redemptions", "fk_cr_redeemed_by"},
            {"coupons", "fk_coupons_created_by"},
            {"job_postings", "fk_jp_creator"},
            {"performance_records", "fk_pr_user"},
            {"promotions", "fk_promotions_created_by"},
            {"property_listings", "fk_pl_listed_by"},
            {"proxy_input_consents", "fk_pic_subject"},
            {"proxy_input_consents", "fk_pic_proxy"},
            {"proxy_input_consents", "fk_pic_witness"},
            {"proxy_input_consents", "fk_pic_approved_by"},
            {"proxy_input_consents", "fk_pic_revoke_wit"},
            {"recruitment_cancellation_policies", "fk_rcp_created_by"},
            {"recruitment_listings", "fk_rl_created_by"},
            {"recruitment_participants", "fk_rp_user"},
            {"recruitment_subcategories", "fk_rs_created_by"},
            {"reservations", "fk_reservations_user"},
            {"resident_documents", "fk_rd_uploaded_by"},
            {"saved_segment_presets", "fk_ssp_created_by"},
            {"shift_budget_allocations", "fk_sba_created_by"},
            {"shift_budget_consumptions", "fk_sbc_user"},
            {"tags", "fk_tags_created_by"},
            {"todo_budget_links", "fk_tbl_creator"},
            {"todo_handoffs", "fk_handoff_from_user"},
    };

    @Test
    @DisplayName("V115.001で全25FK実在_V117.001適用で全25FK撤廃_参照元の孤児保持のままusers親を物理削除できる")
    void 既存データを持つDBでV117_001がRESTRICT_users監査FK25件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        // ── 物理削除対象の users（撤廃対象FKでのみ参照される監査/作成者/本人 user 群）──
        // グループごとに別 user を割り当て、物理削除後の孤児保持を個別に検証する。
        final long advUser;     // advertiser_accounts.approved_by
        final long areUser;     // attendance_requirement_evaluations.resolver_user_id
        final long crUser;      // coupon_redemptions.redeemed_by
        final long couponUser;  // coupons.created_by
        final long jpUser;      // job_postings.created_by_user_id
        final long prUser;      // performance_records.user_id
        final long promoUser;   // promotions.created_by
        final long plUser;      // property_listings.listed_by
        final long picUser;     // proxy_input_consents の5列すべて（退会貫通の核心：同一 user を5列に充てる）
        final long rcpUser;     // recruitment_cancellation_policies.created_by
        final long rlUser;      // recruitment_listings.created_by
        final long rpUser;      // recruitment_participants.user_id
        final long rsUser;      // recruitment_subcategories.created_by
        final long resvUser;    // reservations.user_id
        final long rdUser;      // resident_documents.uploaded_by
        final long sspUser;     // saved_segment_presets.created_by
        final long sbaUser;     // shift_budget_allocations.created_by
        final long sbcUser;     // shift_budget_consumptions.user_id
        final long tagsUser;    // tags.created_by
        final long tblUser;     // todo_budget_links.created_by
        final long handoffUser; // todo_handoffs.from_user_id

        // 撤廃対象FKを監査列に持つ子行 id（物理削除後の孤児保持を検証）
        final long advId;
        final long areId;
        final long crId;
        final long couponId;
        final long jpId;
        final long prId;
        final long promoId;
        final long plId;
        final long picId;
        final long rcpId;
        final long rlId;
        final long rpId;
        final long rsId;
        final long resvId;
        final long rdId;
        final long sspId;
        final long sbaId;
        final long sbcId;
        final long tagsId;
        final long tblId;
        final long handoffId;

        try (Connection c = conn()) {
            // ── given: V115.001 時点では対象25FKが全て実在すること（pre-state sanity）──
            for (String[] fk : TARGET_FKS) {
                assertThat(foreignKeyExists(c, fk[0], fk[1]))
                        .as("V115.001 時点では " + fk[0] + "." + fk[1] + " が実在すること").isTrue();
            }

            // ── 共通の親（物理削除しない・撤廃対象でない RESTRICT/NOT NULL FK を満たすため）──
            long orgParent = insertOrganization(c, "p5c-org-parent");
            long teamParent = insertTeam(c, "p5c-team-parent");

            // ── 1. advertiser_accounts.approved_by → users ──
            // advertiser_accounts: organization_id(RESTRICT→organizations) NOT NULL / company_name / contact_email NOT NULL。approved_by(撤廃対象) をセット。
            advUser = insertUser(c, "p5c-adv@example.com");
            advId = insertAdvertiserAccount(c, orgParent, advUser);

            // ── 2. attendance_requirement_evaluations.resolver_user_id → users ──
            // 親: attendance_requirement_rules（scope CHECK）/ student_attendance_summaries。
            // 注: student_user_id は fk_are_student（RESTRICT → users・本 PR 対象外で残存）で参照されるため、
            //     物理削除する resolver user とは別の studentUser を充て、削除対象は resolver_user_id のみとする。
            areUser = insertUser(c, "p5c-are-resolver@example.com");
            long areStudentUser = insertUser(c, "p5c-are-student@example.com"); // fk_are_student RESTRICT で参照（物理削除しない）
            long arrId = insertAttendanceRequirementRule(c, teamParent);
            long sasId = insertStudentAttendanceSummary(c, teamParent, areStudentUser);
            areId = insertAttendanceRequirementEvaluation(c, arrId, areStudentUser, sasId, areUser);

            // ── 3. coupon_redemptions.redeemed_by → users ──
            // 親チェーン: coupons → coupon_distributions（CASCADE）。
            // 注: coupon_distributions.user_id は fk_cd_user（CASCADE → users）で参照されるため、redeemed_by の物理削除対象 crUser とは
            //     別の distUser を充てる（crUser を distribution.user_id に使うと、crUser 削除が distribution→redemption に CASCADE して
            //     検証対象 crId ごと消えてしまう）。coupons.created_by / coupon_redemptions.redeemed_by には crUser を使い、redeemed_by のみ検証。
            crUser = insertUser(c, "p5c-cr-redeemer@example.com");
            long crDistUser = insertUser(c, "p5c-cr-dist@example.com"); // coupon_distributions.user_id CASCADE 用（物理削除しない）
            long couponForCr = insertCoupon(c, orgParent, crUser);
            long distId = insertCouponDistribution(c, couponForCr, crDistUser);
            crId = insertCouponRedemption(c, distId, crUser);

            // ── 4. coupons.created_by → users ──
            couponUser = insertUser(c, "p5c-coupon@example.com");
            couponId = insertCoupon(c, orgParent, couponUser);

            // ── 5. job_postings.created_by_user_id → users ──
            // job_postings: team_id(CASCADE) NOT NULL + 多数の CHECK（reward/capacity/work_time/deadline）。
            jpUser = insertUser(c, "p5c-jp@example.com");
            jpId = insertJobPosting(c, teamParent, jpUser);

            // ── 6. performance_records.user_id → users ──
            // 親: performance_metrics（team_id NOT NULL）。
            prUser = insertUser(c, "p5c-pr@example.com");
            long metricId = insertPerformanceMetric(c, teamParent);
            prId = insertPerformanceRecord(c, metricId, prUser);

            // ── 7. promotions.created_by → users ──
            promoUser = insertUser(c, "p5c-promo@example.com");
            promoId = insertPromotion(c, orgParent, promoUser);

            // ── 8. property_listings.listed_by → users ──
            // 親: dwelling_units（scope/unit_number）。
            plUser = insertUser(c, "p5c-pl@example.com");
            long dwellingId = insertDwellingUnit(c, orgParent, "p5c-unit-pl");
            plId = insertPropertyListing(c, dwellingId, plUser);

            // ── 9〜13. proxy_input_consents の5列（subject/proxy/witness/approved_by/revoke_wit）→ users ──
            // 退会貫通の核心：5列すべてを同一 picUser にして、V117.001 で5FKを撤廃後にその user を物理削除が貫通することを実証。
            picUser = insertUser(c, "p5c-pic@example.com");
            picId = insertProxyInputConsent(c, picUser, orgParent);

            // ── 14. recruitment_cancellation_policies.created_by → users ──
            rcpUser = insertUser(c, "p5c-rcp@example.com");
            rcpId = insertRecruitmentCancellationPolicy(c, orgParent, rcpUser);

            // ── 15. recruitment_listings.created_by → users ──
            // recruitment_listings: category_id(=1 seed) + 日時 CHECK。
            rlUser = insertUser(c, "p5c-rl@example.com");
            rlId = insertRecruitmentListing(c, rlUser);

            // ── 16. recruitment_participants.user_id → users ──
            // 親: recruitment_listings（created_by は別 user＝物理削除しない rlUser を流用）。USER 参加者は chk_rp_subject（user_id 非NULL・team_id NULL）。
            rpUser = insertUser(c, "p5c-rp@example.com");
            long listingForRp = insertRecruitmentListing(c, rlUser);
            rpId = insertRecruitmentParticipant(c, listingForRp, rpUser);

            // ── 17. recruitment_subcategories.created_by → users ──
            rsUser = insertUser(c, "p5c-rs@example.com");
            rsId = insertRecruitmentSubcategory(c, orgParent, rsUser);

            // ── 18. reservations.user_id → users ──
            // 親チェーン: reservation_slots / reservation_lines（team_id CASCADE）。
            resvUser = insertUser(c, "p5c-resv@example.com");
            long slotId = insertReservationSlot(c, teamParent);
            long lineId = insertReservationLine(c, teamParent);
            resvId = insertReservation(c, slotId, lineId, teamParent, resvUser);

            // ── 19. resident_documents.uploaded_by → users ──
            // 親チェーン: dwelling_units → resident_registry（CASCADE）。
            rdUser = insertUser(c, "p5c-rd@example.com");
            long dwellingForRd = insertDwellingUnit(c, orgParent, "p5c-unit-rd");
            long residentId = insertResidentRegistry(c, dwellingForRd);
            rdId = insertResidentDocument(c, residentId, rdUser);

            // ── 20. saved_segment_presets.created_by → users ──
            sspUser = insertUser(c, "p5c-ssp@example.com");
            sspId = insertSavedSegmentPreset(c, orgParent, sspUser);

            // ── 21+22. shift_budget_allocations.created_by / shift_budget_consumptions.user_id → users ──
            // 親チェーン: budget_fiscal_years → budget_categories（撤廃対象は created_by / user_id のみ）。
            //   shift_id/slot_id（5-B で FK 撤廃済）には任意の値（孤児許容）を入れる。allocation_id は同一ドメイン RESTRICT。
            sbaUser = insertUser(c, "p5c-sba@example.com");
            sbcUser = insertUser(c, "p5c-sbc@example.com");
            long fiscalYearId = insertBudgetFiscalYear(c, orgParent, sbaUser);
            long budgetCategoryId = insertBudgetCategory(c, fiscalYearId);
            sbaId = insertShiftBudgetAllocation(c, orgParent, fiscalYearId, budgetCategoryId, sbaUser);
            sbcId = insertShiftBudgetConsumption(c, sbaId, sbcUser);

            // ── 23. tags.created_by → users ──
            tagsUser = insertUser(c, "p5c-tags@example.com");
            tagsId = insertTag(c, orgParent, tagsUser);

            // ── 24. todo_budget_links.created_by → users ──
            // 親: projects（CASCADE・XOR で project_id を埋める）+ allocation_id（上の sbaId を流用）。
            tblUser = insertUser(c, "p5c-tbl@example.com");
            long projectId = insertProject(c, orgParent, tblUser);
            tblId = insertTodoBudgetLink(c, projectId, sbaId, tblUser);

            // ── 25. todo_handoffs.from_user_id → users ──
            // 親: todos（todo_handoffs.todo_id CASCADE）。todos は scope を満たす。
            handoffUser = insertUser(c, "p5c-handoff@example.com");
            long todoId = insertTodo(c, teamParent, handoffUser);
            handoffId = insertTodoHandoff(c, todoId, handoffUser);
        }

        // ── when: 残り（V117.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象25FKが全て撤廃された ──
            for (String[] fk : TARGET_FKS) {
                assertThat(foreignKeyExists(c, fk[0], fk[1]))
                        .as("V117.001 で " + fk[0] + "." + fk[1] + " が撤廃されること").isFalse();
            }

            // ── then-2（中核）: 各参照先 users 行を物理削除しても、RESTRICT 撤廃済みゆえ
            //   users DELETE がブロックされず成功し、参照元の監査FK列が孤児値を保持する（退会 purge ブロック解消）──

            // 1. advertiser_accounts.approved_by
            assertUserDeleteSucceedsAndOrphanKept(c, advUser, "advertiser_accounts", "approved_by", advId);
            // 2. attendance_requirement_evaluations.resolver_user_id
            assertUserDeleteSucceedsAndOrphanKept(c, areUser, "attendance_requirement_evaluations", "resolver_user_id", areId);
            // 3. coupon_redemptions.redeemed_by
            assertUserDeleteSucceedsAndOrphanKept(c, crUser, "coupon_redemptions", "redeemed_by", crId);
            // 4. coupons.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, couponUser, "coupons", "created_by", couponId);
            // 5. job_postings.created_by_user_id
            assertUserDeleteSucceedsAndOrphanKept(c, jpUser, "job_postings", "created_by_user_id", jpId);
            // 6. performance_records.user_id
            assertUserDeleteSucceedsAndOrphanKept(c, prUser, "performance_records", "user_id", prId);
            // 7. promotions.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, promoUser, "promotions", "created_by", promoId);
            // 8. property_listings.listed_by
            assertUserDeleteSucceedsAndOrphanKept(c, plUser, "property_listings", "listed_by", plId);

            // 9〜13. proxy_input_consents の5列（退会貫通の核心）: 同一 picUser を物理削除が貫通し、5列すべて孤児保持。
            deleteUserPhysically(c, picUser);
            assertThat(rowExists(c, "users", picUser))
                    .as("proxy_input_consents の本人/代理者/立会人/承認者/撤回立会の全列で参照される user が物理削除されたこと（退会貫通）").isFalse();
            assertThat(rowExists(c, "proxy_input_consents", picId))
                    .as("user 物理削除でも proxy_input_consents 子行が生存すること").isTrue();
            assertThat(longColumn(c, "proxy_input_consents", "subject_user_id", picId))
                    .as("proxy_input_consents.subject_user_id が孤児値を保持すること").isEqualTo(picUser);
            assertThat(longColumn(c, "proxy_input_consents", "proxy_user_id", picId))
                    .as("proxy_input_consents.proxy_user_id が孤児値を保持すること").isEqualTo(picUser);
            assertThat(longColumn(c, "proxy_input_consents", "witness_user_id", picId))
                    .as("proxy_input_consents.witness_user_id が孤児値を保持すること").isEqualTo(picUser);
            assertThat(longColumn(c, "proxy_input_consents", "approved_by_user_id", picId))
                    .as("proxy_input_consents.approved_by_user_id が孤児値を保持すること").isEqualTo(picUser);
            assertThat(longColumn(c, "proxy_input_consents", "revoke_witnessed_by_user_id", picId))
                    .as("proxy_input_consents.revoke_witnessed_by_user_id が孤児値を保持すること").isEqualTo(picUser);

            // 14. recruitment_cancellation_policies.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, rcpUser, "recruitment_cancellation_policies", "created_by", rcpId);
            // 15. recruitment_listings.created_by（rlUser は rpId の親 listing の created_by でもあるが、孤児保持で生存するため削除可能）
            assertUserDeleteSucceedsAndOrphanKept(c, rlUser, "recruitment_listings", "created_by", rlId);
            // 16. recruitment_participants.user_id
            assertUserDeleteSucceedsAndOrphanKept(c, rpUser, "recruitment_participants", "user_id", rpId);
            // 17. recruitment_subcategories.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, rsUser, "recruitment_subcategories", "created_by", rsId);
            // 18. reservations.user_id
            assertUserDeleteSucceedsAndOrphanKept(c, resvUser, "reservations", "user_id", resvId);
            // 19. resident_documents.uploaded_by
            assertUserDeleteSucceedsAndOrphanKept(c, rdUser, "resident_documents", "uploaded_by", rdId);
            // 20. saved_segment_presets.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, sspUser, "saved_segment_presets", "created_by", sspId);
            // 21. shift_budget_allocations.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, sbaUser, "shift_budget_allocations", "created_by", sbaId);
            // 22. shift_budget_consumptions.user_id
            assertUserDeleteSucceedsAndOrphanKept(c, sbcUser, "shift_budget_consumptions", "user_id", sbcId);
            // 23. tags.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, tagsUser, "tags", "created_by", tagsId);
            // 24. todo_budget_links.created_by
            assertUserDeleteSucceedsAndOrphanKept(c, tblUser, "todo_budget_links", "created_by", tblId);
            // 25. todo_handoffs.from_user_id
            assertUserDeleteSucceedsAndOrphanKept(c, handoffUser, "todo_handoffs", "from_user_id", handoffId);
        }
    }

    /**
     * RESTRICT 撤廃後の不変条件をまとめて検証するヘルパー:
     * (1) 当該 user の物理 DELETE がブロックされず成功する（退会 purge ブロック解消）、
     * (2) 参照元子行が生存し、(3) 監査FK列が孤児 user_id 値を保持する。
     */
    private void assertUserDeleteSucceedsAndOrphanKept(Connection c, long userId, String childTable,
                                                       String fkColumn, long childId) throws SQLException {
        deleteUserPhysically(c, userId);
        assertThat(rowExists(c, "users", userId))
                .as(childTable + "." + fkColumn + " のみで参照される user が RESTRICT 撤廃により物理削除されること（退会貫通）").isFalse();
        assertThat(rowExists(c, childTable, childId))
                .as("user 物理削除でも " + childTable + " 子行が生存すること").isTrue();
        assertThat(longColumn(c, childTable, fkColumn, childId))
                .as(childTable + "." + fkColumn + " が孤児 user_id を保持すること").isEqualTo(userId);
    }

    // ── 共通 seed helpers ──────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '最終', '5C郎', '最終5C郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** organizations 行を挿入。slug NOT NULL+UNIQUE / org_type='OTHER'。 */
    private long insertOrganization(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organizations
                    (name, org_type, slug, created_at, updated_at)
                VALUES ('最終局面5C監査組織', 'OTHER', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** teams 行を挿入。name/slug NOT NULL / visibility='PUBLIC'。 */
    private long insertTeam(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams
                    (name, slug, visibility, created_at, updated_at)
                VALUES ('最終局面5Cチーム', ?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 1. advertiser_accounts ──

    /** advertiser_accounts: organization_id(RESTRICT) / company_name / contact_email NOT NULL ＋ credit_limit CHECK(>0)。approved_by(撤廃対象) をセット。 */
    private long insertAdvertiserAccount(Connection c, long orgId, long approvedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO advertiser_accounts
                    (scope_type, scope_id, company_name, contact_email, approved_by, approved_at)
                VALUES ('ORGANIZATION', ?, '最終局面5C広告主', 'adv-5c@example.com', ?, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            // 注: organization_id は F09.17 Phase 11-d (V67.026) で撤廃され scope_type/scope_id(NOT NULL) に置換済。
            ps.setLong(1, orgId);
            ps.setLong(2, approvedBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 2. attendance ──

    /** attendance_requirement_rules: academic_year/category/name/effective_from NOT NULL ＋ chk_arr_scope（team xor org）。team スコープ。 */
    private long insertAttendanceRequirementRule(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO attendance_requirement_rules
                    (team_id, academic_year, category, name, effective_from)
                VALUES (?, 2026, 'CUSTOM', '最終局面5C出席要件', '2026-04-01')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** student_attendance_summaries: team_id/student_user_id/academic_year/period_from/period_to NOT NULL ＋ UNIQUE。 */
    private long insertStudentAttendanceSummary(Connection c, long teamId, long studentUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO student_attendance_summaries
                    (team_id, student_user_id, academic_year, period_from, period_to)
                VALUES (?, ?, 2026, '2026-04-01', '2027-03-31')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, studentUserId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** attendance_requirement_evaluations: requirement_rule_id/student_user_id/summary_id/status/evaluated_at NOT NULL。resolver_user_id(撤廃対象) をセット。 */
    private long insertAttendanceRequirementEvaluation(Connection c, long ruleId, long studentUserId,
                                                       long summaryId, long resolverUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO attendance_requirement_evaluations
                    (requirement_rule_id, student_user_id, summary_id, status, evaluated_at, resolver_user_id)
                VALUES (?, ?, ?, 'VIOLATION', NOW(), ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ruleId);
            ps.setLong(2, studentUserId);
            ps.setLong(3, summaryId);
            ps.setLong(4, resolverUserId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 3+4. coupons / coupon_distributions / coupon_redemptions ──

    /** coupons: scope_type(ENUM TEAM/ORGANIZATION)/scope_id/created_by/title/coupon_type/valid_from/valid_until NOT NULL。created_by(撤廃対象=4) をセット。 */
    private long insertCoupon(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO coupons
                    (scope_type, scope_id, created_by, title, coupon_type, valid_from, valid_until)
                VALUES ('ORGANIZATION', ?, ?, '最終局面5Cクーポン', 'FIXED', NOW(), NOW() + INTERVAL 30 DAY)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** coupon_distributions: coupon_id(CASCADE)/user_id(CASCADE)/distributed_at/expires_at NOT NULL。 */
    private long insertCouponDistribution(Connection c, long couponId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO coupon_distributions
                    (coupon_id, user_id, distributed_at, expires_at)
                VALUES (?, ?, NOW(), NOW() + INTERVAL 30 DAY)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, couponId);
            ps.setLong(2, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** coupon_redemptions: distribution_id(CASCADE)/redeemed_by/redeemed_at NOT NULL。redeemed_by(撤廃対象=3) をセット。 */
    private long insertCouponRedemption(Connection c, long distributionId, long redeemedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO coupon_redemptions
                    (distribution_id, redeemed_by, redeemed_at)
                VALUES (?, ?, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, distributionId);
            ps.setLong(2, redeemedBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 5. job_postings ──

    /** job_postings: team_id(CASCADE)/title/description/work_start_at/work_end_at/base_reward_jpy/application_deadline_at NOT NULL ＋ 多数 CHECK。created_by_user_id(撤廃対象) をセット。 */
    private long insertJobPosting(Connection c, long teamId, long createdBy) throws SQLException {
        // CHECK: reward 500〜1000000 / capacity>=1 / work_end_at>work_start_at / application_deadline_at<=work_start_at。
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO job_postings
                    (team_id, created_by_user_id, title, description, work_start_at, work_end_at,
                     base_reward_jpy, application_deadline_at)
                VALUES (?, ?, '最終局面5C求人', '説明', NOW() + INTERVAL 7 DAY, NOW() + INTERVAL 7 DAY + INTERVAL 3 HOUR,
                        5000, NOW() + INTERVAL 5 DAY)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 6. performance_metrics / performance_records ──

    /** performance_metrics: team_id/name NOT NULL。 */
    private long insertPerformanceMetric(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO performance_metrics (team_id, name)
                VALUES (?, '最終局面5C指標')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** performance_records: metric_id(RESTRICT)/user_id(撤廃対象)/recorded_date/value NOT NULL。 */
    private long insertPerformanceRecord(Connection c, long metricId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO performance_records (metric_id, user_id, recorded_date, value)
                VALUES (?, ?, '2026-06-19', 100.0)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, metricId);
            ps.setLong(2, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 7. promotions ──

    /** promotions: scope_type/scope_id/created_by/title NOT NULL ＋ status ENUM。created_by(撤廃対象) をセット。 */
    private long insertPromotion(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO promotions
                    (scope_type, scope_id, created_by, title, status)
                VALUES ('ORGANIZATION', ?, ?, '最終局面5Cプロモ', 'DRAFT')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 8+19. dwelling_units / property_listings / resident_registry / resident_documents ──

    /** dwelling_units: scope_type/unit_number NOT NULL ＋ UNIQUE(team_id,unit_number)/(org_id,unit_number)。org スコープ。 */
    private long insertDwellingUnit(Connection c, long orgId, String unitNumber) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO dwelling_units
                    (scope_type, organization_id, unit_number)
                VALUES ('ORGANIZATION', ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setString(2, unitNumber);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** property_listings: dwelling_unit_id(CASCADE)/listed_by(撤廃対象)/listing_type/title NOT NULL。 */
    private long insertPropertyListing(Connection c, long dwellingUnitId, long listedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO property_listings
                    (dwelling_unit_id, listed_by, listing_type, title)
                VALUES (?, ?, 'SALE', '最終局面5C物件')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, dwellingUnitId);
            ps.setLong(2, listedBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** resident_registry: dwelling_unit_id(CASCADE)/resident_type/last_name/first_name/move_in_date NOT NULL。 */
    private long insertResidentRegistry(Connection c, long dwellingUnitId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO resident_registry
                    (dwelling_unit_id, resident_type, last_name, first_name, move_in_date)
                VALUES (?, 'OWNER', '最終', '5C', '2026-04-01')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, dwellingUnitId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** resident_documents: resident_id(CASCADE)/document_type/file_name/s3_key/file_size/content_type/uploaded_by NOT NULL。uploaded_by(撤廃対象) をセット。 */
    private long insertResidentDocument(Connection c, long residentId, long uploadedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO resident_documents
                    (resident_id, document_type, file_name, s3_key, file_size, content_type, uploaded_by)
                VALUES (?, 'CONTRACT', '最終局面5C.pdf', 'p5c/key/file.pdf', 2048, 'application/pdf', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, residentId);
            ps.setLong(2, uploadedBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 9〜13. proxy_input_consents ──

    /**
     * proxy_input_consents: subject_user_id/proxy_user_id/organization_id/consent_method/effective_from/effective_until NOT NULL。
     * 撤廃対象5列（subject/proxy/witness/approved_by/revoke_wit）すべてに同一 user をセットして、
     * V117.001 で5FKを撤廃後にその user を物理削除が貫通することを実証する（退会 purge ブロック解消の核心）。
     */
    private long insertProxyInputConsent(Connection c, long user, long orgId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO proxy_input_consents
                    (subject_user_id, proxy_user_id, organization_id, consent_method,
                     witness_user_id, effective_from, effective_until,
                     revoke_witnessed_by_user_id, approved_by_user_id)
                VALUES (?, ?, ?, 'PAPER_SIGNED', ?, '2026-04-01', '2027-03-31', ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, user);
            ps.setLong(2, user);
            ps.setLong(3, orgId);
            ps.setLong(4, user);
            ps.setLong(5, user);
            ps.setLong(6, user);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 14+15+16+17. recruitment ──

    /** recruitment_cancellation_policies: scope_type/scope_id/free_until_hours_before/created_by NOT NULL。created_by(撤廃対象) をセット。 */
    private long insertRecruitmentCancellationPolicy(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_cancellation_policies
                    (scope_type, scope_id, free_until_hours_before, created_by)
                VALUES ('ORGANIZATION', ?, 24, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * recruitment_listings: scope_type/scope_id/category_id/title/participation_type/start_at/end_at/
     * application_deadline/auto_cancel_at/capacity/min_capacity/created_by NOT NULL ＋ 日時 CHECK。
     * category_id=1 は V3.116 のシード（futsal_open）。created_by(撤廃対象=15) をセット。
     */
    private long insertRecruitmentListing(Connection c, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_listings
                    (scope_type, scope_id, category_id, title, participation_type,
                     start_at, end_at, application_deadline, auto_cancel_at,
                     capacity, min_capacity, created_by)
                VALUES ('USER', 1, 1, '最終局面5C募集枠', 'INDIVIDUAL',
                        NOW() + INTERVAL 7 DAY, NOW() + INTERVAL 7 DAY + INTERVAL 2 HOUR,
                        NOW() + INTERVAL 5 DAY, NOW() + INTERVAL 4 DAY,
                        10, 2, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** recruitment_participants: listing_id(CASCADE)/participant_type/user_id(撤廃対象=16) NOT NULL ＋ chk_rp_subject（USER なら user_id 非NULL・team_id NULL）。 */
    private long insertRecruitmentParticipant(Connection c, long listingId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_participants
                    (listing_id, participant_type, user_id, status, applied_at)
                VALUES (?, 'USER', ?, 'APPLIED', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, listingId);
            ps.setLong(2, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** recruitment_subcategories: category_id(RESTRICT=1)/scope_type/scope_id/name/created_by NOT NULL。created_by(撤廃対象) をセット。 */
    private long insertRecruitmentSubcategory(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_subcategories
                    (category_id, scope_type, scope_id, name, created_by)
                VALUES (1, 'ORGANIZATION', ?, '最終局面5Cサブカテゴリ', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 18. reservation ──

    /** reservation_slots: team_id(CASCADE)/slot_date/start_time/end_time NOT NULL。 */
    private long insertReservationSlot(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservation_slots (team_id, slot_date, start_time, end_time)
                VALUES (?, '2026-06-20', '09:00:00', '10:00:00')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** reservation_lines: team_id(CASCADE)/name NOT NULL。 */
    private long insertReservationLine(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservation_lines (team_id, name)
                VALUES (?, '最終局面5Cメニュー')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** reservations: reservation_slot_id(RESTRICT)/line_id(RESTRICT)/team_id(CASCADE)/user_id(撤廃対象=18) NOT NULL。 */
    private long insertReservation(Connection c, long slotId, long lineId, long teamId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservations
                    (reservation_slot_id, line_id, team_id, user_id)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, slotId);
            ps.setLong(2, lineId);
            ps.setLong(3, teamId);
            ps.setLong(4, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 20. saved_segment_presets ──

    /** saved_segment_presets: scope_type/scope_id/name/conditions(JSON)/created_by NOT NULL。created_by(撤廃対象) をセット。 */
    private long insertSavedSegmentPreset(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO saved_segment_presets
                    (scope_type, scope_id, name, conditions, created_by)
                VALUES ('ORGANIZATION', ?, '最終局面5Cプリセット', '{}', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 23. tags ──

    /** tags: scope_type/scope_id/name/created_by NOT NULL ＋ UNIQUE(scope_type,scope_id,name)。created_by(撤廃対象) をセット。 */
    private long insertTag(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tags
                    (scope_type, scope_id, name, created_by)
                VALUES ('ORGANIZATION', ?, '最終局面5Cタグ', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 21+22+24. budget chain / projects / shift_budget / todo_budget_links ──

    /** budget_fiscal_years: scope_type/scope_id/name/start_date/end_date/created_by NOT NULL ＋ CHECK(start<end)。 */
    private long insertBudgetFiscalYear(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_fiscal_years
                    (scope_type, scope_id, name, start_date, end_date, created_by)
                VALUES ('ORGANIZATION', ?, '最終局面5C年度', '2026-04-01', '2027-03-31', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** budget_categories: fiscal_year_id(RESTRICT)/name/category_type NOT NULL ＋ chk_bc_category_type。 */
    private long insertBudgetCategory(Connection c, long fiscalYearId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_categories (fiscal_year_id, name, category_type)
                VALUES (?, '最終局面5C費目', 'EXPENSE')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fiscalYearId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** projects: scope_type/scope_id/title/created_by NOT NULL。created_by は別途 RESTRICT だが物理削除しない（tblUser を流用）。 */
    private long insertProject(Connection c, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO projects (scope_type, scope_id, title, created_by)
                VALUES ('ORGANIZATION', ?, '最終局面5Cプロジェクト', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * shift_budget_allocations: organization_id(CASCADE)/fiscal_year_id(RESTRICT)/budget_category_id(RESTRICT)/
     * period_start/period_end/allocated_amount/created_by NOT NULL ＋ CHECK。created_by(撤廃対象=21) をセット。
     */
    private long insertShiftBudgetAllocation(Connection c, long orgId, long fiscalYearId,
                                             long budgetCategoryId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_budget_allocations
                    (organization_id, fiscal_year_id, budget_category_id, period_start, period_end,
                     allocated_amount, created_by)
                VALUES (?, ?, ?, '2026-04-01', '2026-04-30', 100000, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, orgId);
            ps.setLong(2, fiscalYearId);
            ps.setLong(3, budgetCategoryId);
            ps.setLong(4, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * shift_budget_consumptions: allocation_id(RESTRICT・同一ドメイン)/shift_id/slot_id/user_id(撤廃対象=22)/
     * hourly_rate_snapshot/hours/amount NOT NULL ＋ CHECK。
     * shift_id/slot_id への FK は 5-B(V115.001) で撤廃済みのため、孤児許容で任意の値（1）を入れる。
     */
    private long insertShiftBudgetConsumption(Connection c, long allocationId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_budget_consumptions
                    (allocation_id, shift_id, slot_id, user_id, hourly_rate_snapshot, hours, amount)
                VALUES (?, 1, 1, ?, 1200.00, 8.00, 9600.00)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, allocationId);
            ps.setLong(2, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * todo_budget_links: allocation_id(RESTRICT・同一ドメイン)/created_by(撤廃対象=24) NOT NULL ＋ chk_tbl_target_xor
     * （project_id と todo_id は排他）。project_id を埋める。
     */
    private long insertTodoBudgetLink(Connection c, long projectId, long allocationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todo_budget_links
                    (project_id, allocation_id, created_by)
                VALUES (?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, projectId);
            ps.setLong(2, allocationId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 25. todos / todo_handoffs ──

    /** todos: team スコープで作成（todo_handoffs.todo_id CASCADE の親）。NOT NULL/scope を満たす。 */
    private long insertTodo(Connection c, long teamId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todos
                    (scope_type, scope_id, title, status, priority, created_by, created_at, updated_at)
                VALUES ('TEAM', ?, '最終局面5C TODO', 'OPEN', 'MEDIUM', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * todo_handoffs: todo_id(CASCADE)/from_user_id(撤廃対象=25)/from_assignee_user_ids(JSON)/to_assignee_user_ids(JSON)/
     * previous_status/new_status NOT NULL ＋ chk_handoff_prev/new_status（OPEN/IN_PROGRESS/COMPLETED）。
     */
    private long insertTodoHandoff(Connection c, long todoId, long fromUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todo_handoffs
                    (todo_id, from_user_id, from_assignee_user_ids, to_assignee_user_ids,
                     previous_status, new_status, created_at)
                VALUES (?, ?, '[]', '[]', 'OPEN', 'IN_PROGRESS', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, todoId);
            ps.setLong(2, fromUserId);
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

    private void deleteUserPhysically(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
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
