package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2782 — {@code ResultsVisibility.ALWAYS} の配信母集団照会が「組織数に比例」する欠陥の検証（試練・テスト先行）。
 *
 * <p><b>課題</b>: {@code SurveyVisibilityResolver#prepareAdditionalAxisContext} は、組織スコープの
 * {@code ALWAYS} アンケートについて {@code OrganizationMembershipService#isInOrgDistributionAudience} を
 * <b>({@code 組織}, {@code include_supporters}) の組ごとに 1 回</b>呼ぶ。別組織のアンケートを
 * {@code filterAccessible} にまとめて渡すと、組織の種類数に比例して再帰 EXISTS が発行される。
 * これは基盤 {@code AbstractContentVisibilityResolver#prepareAdditionalAxisContext} の
 * 「判定ループに入る前に必要な集合をバッチ 1 本で引く」契約に正面から反し、設計書
 * {@code docs/features/F00_content_visibility_resolver.md} §9 の SQL 本数上限（最大 7 本）も破りうる。</p>
 *
 * <p><b>⚠️ なぜ {@code AFTER_CLOSE}（PR #2801 / Issue #2774）と同じ手が使えないのか</b> —
 * {@code AFTER_CLOSE} は「スコープ所属者全員」であるため、所属軸である
 * {@code UserScopeRoleSnapshot#isDescendantMemberOf} へそのまま寄せられ、追加クエリ 0 本になった。
 * しかし {@code ALWAYS} は<b>配信母集団</b>であり、次を参照しなければ表現できない:</p>
 * <ul>
 *   <li>{@code distribution_mode = TARGETED} → {@code survey_targets} 名簿のみ（所属では判定できない）</li>
 *   <li>{@code distribution_mode = ALL} × ORGANIZATION → 配下ツリー、かつ
 *       {@code include_supporters = FALSE} なら<b>純 SUPPORTER を除外</b>する</li>
 * </ul>
 * <p>snapshot の所属軸は G7 により SUPPORTER を一律含むため、これを表せない。意味論を壊してまで
 * 寄せると「配信されていない者に中間集計が見える（漏洩）」か「配信された者が 403（機能不全）」に
 * なるため、<b>母集団の意味論を保ったまま複数 ORG 根をバルク化する</b>のが本 Issue の解である。</p>
 *
 * <p><b>実 DB で書く理由</b>: 所属は {@code user_roles}（権限ロール）と {@code memberships}
 * （MEMBER / SUPPORTER）の 2 系統に分かれており、スタブしたユニットテストでは 2 系統の合流も
 * 純 SUPPORTER 除外の MEMBER 優先規約も再現できない。スタブ化すると本番だけが壊れたまま green に
 * なるため、Testcontainers の実 MySQL に seed を投入して実挙動を測る。</p>
 *
 * <p><b>アサーションの方針</b>: 「新旧 2 述語が一致するか」だけを置くと、<b>双方が等しく壊れていれば
 * 偽 green</b> になる。よって同値性の突き合わせに加えて、被験者ごとの<b>期待絶対値</b>を必ず併記する。</p>
 *
 * <p><b>関連</b>: Issue #2774 / PR #2801（{@code AFTER_CLOSE} 側の同型 N+1 を根治済み）、
 * Issue #2780 / #2785（組織配下の所属判定を {@code user_roles} ∪ {@code memberships} へ是正）、
 * CMP-017（認可漏れ全域監査戦役）。</p>
 */
@Transactional
@DisplayName("Issue #2782 ALWAYS の配信母集団照会を組織数に比例させない")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SurveyVisibilityResolverAlwaysAudienceBatchIT extends AbstractMySqlIntegrationTest {

    /**
     * 組織配信母集団の照会 SQL を識別する印。
     *
     * <p>純 SUPPORTER 除外節のリテラル比較は<b>配信母集団の述語にしか存在しない</b>。
     * 所属軸である snapshot の下向き再帰クエリは {@code CAST(ms.role_kind AS CHAR)} で
     * 列を射影するだけでリテラル比較を持たないため、この印で両者を取り違えずに数えられる。</p>
     */
    private static final String AUDIENCE_SQL_MARKER = "role_kind = 'SUPPORTER'";

    @Autowired
    private ContentVisibilityChecker checker;

    @Autowired
    private OrganizationMembershipService organizationMembershipService;

    @PersistenceContext
    private EntityManager em;

    /** 4 つの根組織。それぞれ配下組織を 1 つ持ち、その配下組織に ACTIVE なチームが参加する。 */
    private final Long[] rootOrgIds = new Long[4];
    private final Long[] childOrgIds = new Long[4];
    private final Long[] teamIds = new Long[4];

    /** 全 4 組織の配下チームに在籍する一般メンバー（memberships 専属・配下チーム経路）。 */
    private Long memberUserId;
    /** 組織 1 の配下チームの純 SUPPORTER（MEMBER 所属を持たない）。 */
    private Long supporterUserId;
    /** 組織 1 で SUPPORTER と MEMBER を兼ねる者（MEMBER 優先で母集団に残る）。 */
    private Long dualUserId;
    /** 組織 1 の配下組織へ直属する一般メンバー（memberships 専属・配下組織経路）。 */
    private Long orgDirectUserId;
    /** どのスコープにも所属しない利用者。 */
    private Long outsiderUserId;
    /** 組織 1 の配下チームを退会済み（{@code memberships.left_at IS NOT NULL}）の利用者。 */
    private Long leftUserId;
    /** 在籍しているが利用者が非 ACTIVE（{@code users.status}）。 */
    private Long inactiveUserId;
    /** 在籍しているが利用者が論理削除済み（{@code users.deleted_at}）。 */
    private Long deletedUserId;
    /** アンケート作成者（プラットフォーム管理者・被験者にはしない）。 */
    private Long creatorUserId;

    @BeforeEach
    void setUp() {
        memberUserId = insertUser("sv2782.member@example.com");
        supporterUserId = insertUser("sv2782.supporter@example.com");
        dualUserId = insertUser("sv2782.dual@example.com");
        orgDirectUserId = insertUser("sv2782.orgdirect@example.com");
        outsiderUserId = insertUser("sv2782.outsider@example.com");
        leftUserId = insertUser("sv2782.left@example.com");
        inactiveUserId = insertUser("sv2782.inactive@example.com");
        deletedUserId = insertUser("sv2782.deleted@example.com");
        creatorUserId = insertUser("sv2782.creator@example.com");
        MembershipTestHelper.insertUserRole(em, creatorUserId, "SYSTEM_ADMIN", null, null);

        for (int i = 0; i < 4; i++) {
            rootOrgIds[i] = insertOrganization("2782 根組織" + i, null);
            childOrgIds[i] = insertOrganization("2782 配下組織" + i, rootOrgIds[i]);
            teamIds[i] = insertTeam("2782 配下チーム" + i);
            insertTeamOrgMembership(teamIds[i], childOrgIds[i]);
            // 配下チーム経路の一般メンバー（全組織に在籍させ、組織横断のバッチを成立させる）。
            MembershipTestHelper.insertMembership(
                    em, memberUserId, ScopeType.TEAM, teamIds[i], RoleKind.MEMBER);
        }

        // 組織 1 だけに置く被験者群（母集団の意味論を測る）。
        MembershipTestHelper.insertMembership(
                em, supporterUserId, ScopeType.TEAM, teamIds[0], RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(
                em, dualUserId, ScopeType.TEAM, teamIds[0], RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(
                em, dualUserId, ScopeType.ORGANIZATION, childOrgIds[0], RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(
                em, orgDirectUserId, ScopeType.ORGANIZATION, childOrgIds[0], RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(
                em, leftUserId, ScopeType.TEAM, teamIds[0], RoleKind.MEMBER);
        markMembershipLeft(leftUserId, teamIds[0]);
        MembershipTestHelper.insertMembership(
                em, inactiveUserId, ScopeType.TEAM, teamIds[0], RoleKind.MEMBER);
        markUserStatus(inactiveUserId, "FROZEN");
        MembershipTestHelper.insertMembership(
                em, deletedUserId, ScopeType.TEAM, teamIds[0], RoleKind.MEMBER);
        markUserDeleted(deletedUserId);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // AC-1 / AC-2【N+1】組織数に比例しないこと
    // =========================================================================

    /**
     * AC-1【N+1】: 3 組織以上の {@code ALWAYS} アンケートを {@code filterAccessible} にまとめて
     * 渡したとき、組織母集団の照会は<b>組織数に比例しない</b>（トグルが単一なら 1 本、最悪でも 2 本）。
     *
     * <p>改修前は組織ごとに単発 EXISTS を撃つため 4 本発行され red となる。</p>
     */
    @Test
    @DisplayName("AC-1 4 組織の ALWAYS を一括判定しても母集団照会は 1〜2 本に収まる")
    void ac1_audienceQueryDoesNotScaleWithOrganizationCount() {
        List<Long> surveyIds = List.of(
                insertOrgAlwaysSurvey("2782-ac1-0", rootOrgIds[0], false),
                insertOrgAlwaysSurvey("2782-ac1-1", rootOrgIds[1], false),
                insertOrgAlwaysSurvey("2782-ac1-2", rootOrgIds[2], false),
                insertOrgAlwaysSurvey("2782-ac1-3", rootOrgIds[3], false));
        em.flush();
        em.clear();

        SqlIntentCounter.reset();
        var visible = checker.filterAccessible(ReferenceType.SURVEY, surveyIds, memberUserId);

        assertThat(SqlIntentCounter.intentCount(AUDIENCE_SQL_MARKER))
                .as("AC-1: 母集団照会は組織数(4)に比例してはならない。発行 SQL=%s",
                        SqlIntentCounter.capturedSqls())
                .isLessThanOrEqualTo(2);
        // 本数だけを見ると「照会を消したので 0 本」でも通ってしまうため、判定結果も同時に固定する。
        assertThat(visible)
                .as("AC-1: 全組織の配下チームに在籍するため全件可視であること")
                .containsExactlyInAnyOrderElementsOf(surveyIds);
    }

    /**
     * AC-2【N+1】: {@code include_supporters} が true と false のアンケートが混在しても、
     * 照会本数は増え続けない（トグルは 2 値なので最悪 2 本に収まる）。
     */
    @Test
    @DisplayName("AC-2 include_supporters が混在しても母集団照会は 2 本を超えない")
    void ac2_audienceQueryDoesNotScaleWithMixedSupporterToggle() {
        List<Long> surveyIds = List.of(
                insertOrgAlwaysSurvey("2782-ac2-0", rootOrgIds[0], false),
                insertOrgAlwaysSurvey("2782-ac2-1", rootOrgIds[1], true),
                insertOrgAlwaysSurvey("2782-ac2-2", rootOrgIds[2], false),
                insertOrgAlwaysSurvey("2782-ac2-3", rootOrgIds[3], true));
        em.flush();
        em.clear();

        SqlIntentCounter.reset();
        var visible = checker.filterAccessible(ReferenceType.SURVEY, surveyIds, memberUserId);

        assertThat(SqlIntentCounter.intentCount(AUDIENCE_SQL_MARKER))
                .as("AC-2: トグル混在でも (組織数×トグル数) に比例してはならない。発行 SQL=%s",
                        SqlIntentCounter.capturedSqls())
                .isLessThanOrEqualTo(2);
        assertThat(visible)
                .as("AC-2: 一般メンバーはトグルによらず全件可視であること")
                .containsExactlyInAnyOrderElementsOf(surveyIds);
    }

    // =========================================================================
    // AC-3【同値性】新実装と従来の per-org 述語が完全に一致すること
    // =========================================================================

    /**
     * AC-3【同値性】: {@code ALWAYS} の可視判定（改修後はバルク照会を通る）と、従来の
     * {@code isInOrgDistributionAudience} を<b>組織ごとに呼んだ結果</b>が、実 DB 上で
     * すべての被験者・両トグルについて完全に一致する。
     *
     * <p><b>期待絶対値を必ず併記する</b>。一致だけを見ると、双方が等しく壊れたときに
     * 偽 green になるためである（本戦役で 3 度踏んだ罠）。</p>
     */
    @Test
    @DisplayName("AC-3 バルク判定と従来の per-org 述語が完全に一致する（期待絶対値も固定）")
    void ac3_bulkAudienceMatchesLegacyPerOrgPredicate() {
        for (boolean includeSupporters : new boolean[] {false, true}) {
            Long surveyId = insertOrgAlwaysSurvey(
                    "2782-ac3-" + includeSupporters, rootOrgIds[0], includeSupporters);
            em.flush();
            em.clear();

            Map<Long, Boolean> expected = new LinkedHashMap<>();
            expected.put(memberUserId, true);
            expected.put(orgDirectUserId, true);
            expected.put(dualUserId, true);
            // 純 SUPPORTER のみトグルで結果が変わる。ここが所属軸へ寄せられない理由そのもの。
            expected.put(supporterUserId, includeSupporters);
            expected.put(outsiderUserId, false);
            expected.put(leftUserId, false);
            expected.put(inactiveUserId, false);
            expected.put(deletedUserId, false);

            for (Map.Entry<Long, Boolean> e : expected.entrySet()) {
                Long userId = e.getKey();
                boolean viaLegacy = organizationMembershipService.isInOrgDistributionAudience(
                        rootOrgIds[0], userId, includeSupporters);
                boolean viaResolver = checker.canView(ReferenceType.SURVEY, surveyId, userId);

                assertThat(viaLegacy)
                        .as("AC-3 従来述語の絶対値 userId=%s includeSupporters=%s",
                                userId, includeSupporters)
                        .isEqualTo(e.getValue());
                assertThat(viaResolver)
                        .as("AC-3 Resolver 経由の絶対値 userId=%s includeSupporters=%s",
                                userId, includeSupporters)
                        .isEqualTo(e.getValue());
                assertThat(viaResolver)
                        .as("AC-3 2 述語の一致 userId=%s includeSupporters=%s",
                                userId, includeSupporters)
                        .isEqualTo(viaLegacy);
            }
        }
    }

    // =========================================================================
    // AC-4 / AC-5 応援者トグルの意味論
    // =========================================================================

    /**
     * AC-4: {@code include_supporters = FALSE} では純 SUPPORTER が母集団から除外される。
     * ただし MEMBER を兼ねる者は MEMBER 優先で残る。
     */
    @Test
    @DisplayName("AC-4 include_supporters=FALSE は純 SUPPORTER を除外し、MEMBER 兼務者は残す")
    void ac4_pureSupporterExcludedButDualMemberKept() {
        Long surveyId = insertOrgAlwaysSurvey("2782-ac4", rootOrgIds[0], false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, supporterUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, dualUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isTrue();
    }

    /** AC-5: {@code include_supporters = TRUE} では SUPPORTER も母集団に含まれる。 */
    @Test
    @DisplayName("AC-5 include_supporters=TRUE では SUPPORTER も母集団に含まれる")
    void ac5_supporterIncludedWhenToggleOn() {
        Long surveyId = insertOrgAlwaysSurvey("2782-ac5", rootOrgIds[0], true);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, supporterUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, dualUserId)).isTrue();
    }

    // =========================================================================
    // AC-6 memberships 専属の一般メンバー（配下組織・配下チームの両経路）
    // =========================================================================

    /**
     * AC-6: {@code memberships} にしか在籍行を持たない一般メンバーが、配下組織直属・配下チームの
     * <b>両経路</b>で母集団に含まれる（#2780 / #2785 の是正に追随していること）。
     *
     * <p>候補集合を {@code user_roles} だけで組むと、V60.010 以降の本番で唯一成立しうる
     * 素メンバーを構造的に取りこぼす。</p>
     */
    @Test
    @DisplayName("AC-6 memberships 専属メンバーは配下組織・配下チームの両経路で母集団に含まれる")
    void ac6_membershipsOnlyMembersIncludedViaBothPaths() {
        Long surveyId = insertOrgAlwaysSurvey("2782-ac6", rootOrgIds[0], false);
        em.flush();
        em.clear();

        // 配下組織へ直属する経路。
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, orgDirectUserId)).isTrue();
        // 配下組織に参加する ACTIVE チーム経由の経路。
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isTrue();
    }

    // =========================================================================
    // AC-7【陽性対照】締める方向の境界
    // =========================================================================

    /**
     * AC-7【陽性対照】: スコープ外・退会済（{@code left_at IS NOT NULL}）・論理削除済・
     * 非 ACTIVE の利用者は母集団に含まれない。バルク化が「誰でも可視」へ緩む退行を止める。
     */
    @Test
    @DisplayName("AC-7【陽性対照】圏外・退会済・論理削除済・非 ACTIVE は母集団に含まれない")
    void ac7_negativeControlsRemainExcluded() {
        Long surveyId = insertOrgAlwaysSurvey("2782-ac7", rootOrgIds[0], true);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, leftUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, deletedUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, inactiveUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
        // 別組織のメンバーは当該組織の母集団に入らない（根をまたいだ取り違えの検出）。
        Long otherOrgSurveyId = insertOrgAlwaysSurvey("2782-ac7-other", rootOrgIds[1], true);
        em.flush();
        em.clear();
        assertThat(checker.canView(ReferenceType.SURVEY, otherOrgSurveyId, supporterUserId))
                .as("AC-7: 組織 1 の SUPPORTER は組織 2 の母集団に入らない")
                .isFalse();
    }

    /**
     * AC-8【陽性対照】: {@code TARGETED} は従来どおり {@code survey_targets} 名簿のみで判定される。
     * 本改修で母集団判定へ化けていないことを固定する（名簿に載らない在籍者は不可視のまま）。
     */
    @Test
    @DisplayName("AC-8【陽性対照】TARGETED は名簿のみで判定される（母集団へ化けない）")
    void ac8_targetedStillDecidedByRosterOnly() {
        Long surveyId = insertOrgSurvey("2782-ac8", rootOrgIds[0], false, "TARGETED");
        insertSurveyTarget(surveyId, orgDirectUserId);
        em.flush();
        em.clear();

        // 名簿に載っている者だけが可視。
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, orgDirectUserId)).isTrue();
        // 在籍していても名簿に載っていなければ不可視（所属では通さない）。
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
    }

    /**
     * AC-9: 組織横断のバッチでも、F00 設計書 §9 の SQL 本数上限（7 本）を超えない。
     */
    @Test
    @DisplayName("AC-9 組織横断バッチでも SQL 本数上限（7 本）を超えない")
    void ac9_batchStaysWithinSqlBudget() {
        List<Long> surveyIds = List.of(
                insertOrgAlwaysSurvey("2782-ac9-0", rootOrgIds[0], false),
                insertOrgAlwaysSurvey("2782-ac9-1", rootOrgIds[1], false),
                insertOrgAlwaysSurvey("2782-ac9-2", rootOrgIds[2], true),
                insertOrgAlwaysSurvey("2782-ac9-3", rootOrgIds[3], true));
        em.flush();
        em.clear();

        SqlIntentCounter.reset();
        checker.filterAccessible(ReferenceType.SURVEY, surveyIds, memberUserId);

        assertThat(SqlIntentCounter.totalCount())
                .as("AC-9: 設計書 §9 の上限 7 本。発行 SQL=%s", SqlIntentCounter.capturedSqls())
                .isLessThanOrEqualTo(7);
    }

    // =========================================================================
    // seed ヘルパー
    // =========================================================================

    private Long insertUser(String email) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, '姓', '名', :email, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, Long parentOrganizationId) {
        String parentExpr = parentOrganizationId == null ? "NULL" : parentOrganizationId.toString();
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, parent_organization_id, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), " + parentExpr + ", NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long team, Long org) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", team)
                .setParameter("oid", org)
                .executeUpdate();
    }

    private void markMembershipLeft(Long userId, Long scopeId) {
        em.createNativeQuery(
                "UPDATE memberships SET left_at = NOW(), updated_at = NOW() "
                        + "WHERE user_id = :uid AND scope_type = 'TEAM' AND scope_id = :sid")
                .setParameter("uid", userId)
                .setParameter("sid", scopeId)
                .executeUpdate();
    }

    private void markUserStatus(Long userId, String status) {
        em.createNativeQuery("UPDATE users SET status = :st WHERE id = :uid")
                .setParameter("st", status)
                .setParameter("uid", userId)
                .executeUpdate();
    }

    private void markUserDeleted(Long userId) {
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :uid")
                .setParameter("uid", userId)
                .executeUpdate();
    }

    private Long insertOrgAlwaysSurvey(String title, Long orgId, boolean includeSupporters) {
        return insertOrgSurvey(title, orgId, includeSupporters, "ALL");
    }

    /**
     * 組織スコープの {@code ALWAYS} アンケートを 1 行 INSERT する。
     *
     * <p>作成者は被験者と重ならない利用者に固定する。作成者本人は Service 側の高速パスで
     * Resolver を通らないため、作成者を被験者にすると母集団判定の有無を measure できない。</p>
     */
    private Long insertOrgSurvey(String title, Long orgId, boolean includeSupporters,
                                 String distributionMode) {
        em.createNativeQuery(
                "INSERT INTO surveys ("
                        + "scope_type, scope_id, title, status, "
                        + "is_anonymous, allow_multiple_submissions, results_visibility, "
                        + "distribution_mode, include_supporters, unresponded_visibility, "
                        + "auto_post_to_timeline, manual_remind_count, response_count, target_count, "
                        + "version, created_by, expires_at, "
                        + "created_at, updated_at) "
                        + "VALUES ('ORGANIZATION', :scopeId, :title, 'PUBLISHED', "
                        + "0, 0, 'ALWAYS', "
                        + ":distributionMode, :includeSupporters, 'CREATOR_AND_ADMIN', "
                        + "0, 0, 0, 0, "
                        + "0, :createdBy, NULL, "
                        + "NOW(), NOW())")
                .setParameter("scopeId", orgId)
                .setParameter("title", title)
                .setParameter("distributionMode", distributionMode)
                .setParameter("includeSupporters", includeSupporters ? 1 : 0)
                .setParameter("createdBy", creatorUserId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM surveys WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    private void insertSurveyTarget(Long surveyId, Long userId) {
        em.createNativeQuery(
                "INSERT INTO survey_targets (survey_id, user_id, created_at) "
                        + "VALUES (:sid, :uid, NOW())")
                .setParameter("sid", surveyId)
                .setParameter("uid", userId)
                .executeUpdate();
    }
}
