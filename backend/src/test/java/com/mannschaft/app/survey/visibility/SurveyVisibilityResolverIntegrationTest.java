package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase C — {@link SurveyVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、Resolver と
 * {@link ContentVisibilityChecker} を組み立てた上で CUSTOM 3 値
 * (AFTER_RESPONSE / AFTER_CLOSE / VIEWERS_ONLY) と status × visibility 合成を
 * 包括的に検証する。</p>
 *
 * <p>{@code MembershipBatchQueryServiceIntegrationTest} および
 * {@code EventVisibilityResolverIntegrationTest} の方式を踏襲する。
 * すなわち {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / organizations / teams / team_org_memberships / roles / user_roles
 * および surveys / survey_responses / survey_result_viewers を直接 INSERT する。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("SurveyVisibilityResolver 結合テスト")
class SurveyVisibilityResolverIntegrationTest {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private ContentVisibilityChecker checker;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long adminRoleId;
    private Long systemAdminRoleId;
    private Long memberUserId;
    private Long adminUserId;
    private Long viewerUserId;
    private Long nonMemberUserId;
    private Long sysAdminUserId;
    private Long teamId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('ADMIN', '管理者', 2, 0, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        adminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'ADMIN'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        memberUserId = insertUser("sv.member@example.com", "山田", "太郎");
        adminUserId = insertUser("sv.admin@example.com", "佐藤", "次郎");
        viewerUserId = insertUser("sv.viewer@example.com", "高橋", "三郎");
        nonMemberUserId = insertUser("sv.nonmember@example.com", "鈴木", "花子");
        sysAdminUserId = insertUser("sv.sysadmin@example.com", "管理", "者");

        orgId = insertOrganization("SV結合 組織");
        teamId = insertTeam("SV結合 チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId, memberRoleId, teamId, null);
        insertUserRole(adminUserId, adminRoleId, teamId, null);
        insertUserRole(viewerUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        em.flush();
        em.clear();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamId, Long orgId) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    /**
     * surveys テーブルへ最小 NOT NULL 全列を直接 INSERT する。
     *
     * @param expiresAt 締切日時 ({@code null} 可)
     * @return 生成された survey_id
     */
    private Long insertSurvey(String title, Long createdBy, String status, String resultsVisibility,
                              LocalDateTime expiresAt) {
        String expiresExpr = expiresAt == null ? "NULL" : "'" + expiresAt.format(DT_FMT) + "'";
        em.createNativeQuery(
                "INSERT INTO surveys ("
                        + "scope_type, scope_id, title, status, "
                        + "is_anonymous, allow_multiple_submissions, results_visibility, "
                        + "distribution_mode, unresponded_visibility, auto_post_to_timeline, "
                        + "manual_remind_count, response_count, target_count, "
                        + "version, created_by, expires_at, "
                        + "created_at, updated_at) "
                        + "VALUES ('TEAM', :scopeId, :title, :status, "
                        + "0, 0, :resultsVisibility, "
                        + "'ALL', 'CREATOR_AND_ADMIN', 0, "
                        + "0, 0, 0, "
                        + "0, :createdBy, " + expiresExpr + ", "
                        + "NOW(), NOW())")
                .setParameter("scopeId", teamId)
                .setParameter("title", title)
                .setParameter("status", status)
                .setParameter("resultsVisibility", resultsVisibility)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM surveys WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    /**
     * survey_result_viewers に limited リスト登録する。
     */
    private void insertResultViewer(Long surveyId, Long userId) {
        em.createNativeQuery(
                "INSERT INTO survey_result_viewers (survey_id, user_id, created_at) "
                        + "VALUES (:sid, :uid, NOW())")
                .setParameter("sid", surveyId)
                .setParameter("uid", userId)
                .executeUpdate();
    }

    /**
     * survey_questions に最小設問を 1 件投入し、その question_id を返す。
     * survey_responses が question_id NOT NULL 制約を持つため必要。
     */
    private Long insertQuestion(Long surveyId) {
        em.createNativeQuery(
                "INSERT INTO survey_questions ("
                        + "survey_id, question_text, question_type, "
                        + "is_required, display_order, "
                        + "created_at) "
                        + "VALUES (:sid, '質問', 'FREE_TEXT', 0, 0, NOW())")
                .setParameter("sid", surveyId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM survey_questions WHERE survey_id = :sid ORDER BY id DESC LIMIT 1")
                .setParameter("sid", surveyId)
                .getSingleResult()).longValue();
    }

    /**
     * survey_responses に「回答済み」レコードを 1 件投入する。
     */
    private void insertResponse(Long surveyId, Long questionId, Long userId) {
        em.createNativeQuery(
                "INSERT INTO survey_responses (survey_id, question_id, user_id, text_response, created_at, updated_at) "
                        + "VALUES (:sid, :qid, :uid, '回答テキスト', NOW(), NOW())")
                .setParameter("sid", surveyId)
                .setParameter("qid", questionId)
                .setParameter("uid", userId)
                .executeUpdate();
    }

    // =========================================================================
    // ADMINS_ONLY シナリオ (CUSTOM ではない、Mapper 直結)
    // =========================================================================

    @Test
    @DisplayName("ADMINS_ONLY × PUBLISHED は ADMIN のみ閲覧可")
    void admins_only_visible_to_admin_only() {
        Long surveyId = insertSurvey("sv-admin", memberUserId, "PUBLISHED", "ADMINS_ONLY", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, adminUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // CUSTOM: AFTER_RESPONSE
    // =========================================================================

    @Test
    @DisplayName("AFTER_RESPONSE — 回答済み MEMBER のみ閲覧可、未回答者は不可視")
    void after_response_visible_only_to_responded() {
        Long surveyId = insertSurvey("sv-after-resp", memberUserId, "PUBLISHED", "AFTER_RESPONSE", null);
        Long questionId = insertQuestion(surveyId);
        insertResponse(surveyId, questionId, memberUserId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, viewerUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // CUSTOM: AFTER_CLOSE
    // =========================================================================

    @Test
    @DisplayName("AFTER_CLOSE — expiresAt が過去なら誰でも閲覧可、未来なら一般ユーザー不可視")
    void after_close_visibility() {
        Long expiredId = insertSurvey("sv-after-close-expired", memberUserId, "CLOSED",
                "AFTER_CLOSE", LocalDateTime.now().minusHours(1));
        Long activeId = insertSurvey("sv-after-close-active", memberUserId, "PUBLISHED",
                "AFTER_CLOSE", LocalDateTime.now().plusHours(1));
        em.flush();
        em.clear();

        // 締切後: 誰でも可視
        assertThat(checker.canView(ReferenceType.SURVEY, expiredId, null)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, expiredId, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, expiredId, nonMemberUserId)).isTrue();

        // 未締切: 一般ユーザーは不可視、SystemAdmin のみ可視
        assertThat(checker.canView(ReferenceType.SURVEY, activeId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, activeId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, activeId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("AFTER_CLOSE — expiresAt = NULL は fail-closed (SystemAdmin 以外不可視)")
    void after_close_no_expires_fail_closed() {
        Long surveyId = insertSurvey("sv-after-close-null", memberUserId, "PUBLISHED",
                "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // CUSTOM: VIEWERS_ONLY
    // =========================================================================

    @Test
    @DisplayName("VIEWERS_ONLY — survey_result_viewers に登録ユーザーのみ閲覧可")
    void viewers_only_visible_to_registered() {
        Long surveyId = insertSurvey("sv-viewers", memberUserId, "PUBLISHED", "VIEWERS_ONLY", null);
        insertResultViewer(surveyId, viewerUserId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, viewerUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // status × visibility 合成
    // =========================================================================

    @Test
    @DisplayName("DRAFT は作成者本人および SystemAdmin のみ閲覧可")
    void draft_visible_to_author_or_sysadmin_only() {
        Long surveyId = insertSurvey("sv-draft", memberUserId, "DRAFT", "ADMINS_ONLY", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("ARCHIVED は SystemAdmin のみ閲覧可")
    void archived_visible_to_sysadmin_only() {
        Long surveyId = insertSurvey("sv-archived", memberUserId, "ARCHIVED", "ADMINS_ONLY", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, adminUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // 不存在 / バッチ
    // =========================================================================

    @Test
    @DisplayName("不存在 ID は誰に対しても false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.SURVEY, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, 999_999L, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible は CUSTOM 混在でも正しくフィルタする")
    void filterAccessible_mixed_custom() {
        Long s1 = insertSurvey("sv-flt-1", memberUserId, "PUBLISHED", "ADMINS_ONLY", null);
        Long s2 = insertSurvey("sv-flt-2", memberUserId, "CLOSED", "AFTER_CLOSE",
                LocalDateTime.now().minusHours(1));
        Long s3 = insertSurvey("sv-flt-3", memberUserId, "PUBLISHED", "VIEWERS_ONLY", null);
        insertResultViewer(s3, viewerUserId);
        em.flush();
        em.clear();

        // viewerUserId (MEMBER, viewers 登録あり) → s2(締切後で誰でも可視) と s3(viewers) のみ
        Set<Long> viewerSet = checker.filterAccessible(
                ReferenceType.SURVEY, List.of(s1, s2, s3), viewerUserId);
        assertThat(viewerSet).containsExactlyInAnyOrder(s2, s3);

        // adminUserId → s1(ADMIN) と s2(締切後) のみ
        Set<Long> adminSet = checker.filterAccessible(
                ReferenceType.SURVEY, List.of(s1, s2, s3), adminUserId);
        assertThat(adminSet).containsExactlyInAnyOrder(s1, s2);

        // sysAdmin → 全件（高速パス）
        Set<Long> sysAdminSet = checker.filterAccessible(
                ReferenceType.SURVEY, List.of(s1, s2, s3), sysAdminUserId);
        assertThat(sysAdminSet).containsExactlyInAnyOrder(s1, s2, s3);
    }
}
