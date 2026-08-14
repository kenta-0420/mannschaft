package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2774 — 結果閲覧の可視性判定に「スコープ所属の確認」が入らない経路の検証（試練・テスト先行）。
 *
 * <p><b>課題</b>: {@code ResultsVisibility.AFTER_CLOSE} は {@code CUSTOM} 経路へ流れるが、
 * その判定は締切という<b>時間条件のみ</b>で真偽が決まり、閲覧者がアンケートのスコープに
 * 所属しているかを一度も参照しない。同じ {@code CUSTOM} 経路でも
 * {@code AFTER_RESPONSE} は回答履歴を、{@code VIEWERS_ONLY} は結果閲覧者名簿を照合しており、
 * 時間条件だけの値がスコープ確認を持たない点が<b>非対称</b>である。
 * {@code surveys.results_visibility} の DB 既定値が {@code AFTER_CLOSE} であるため、
 * 明示設定していないアンケートがすべて該当する。</p>
 *
 * <p><b>期待する不変条件</b>: 締切を過ぎていることは可視の<b>必要条件であって十分条件ではない</b>。
 * 所属軸（配信母集団と同一の述語）の確認を AND で併せて要求する。</p>
 *
 * <p><b>実 DB で書く理由</b>: 所属は {@code user_roles}（権限ロール）と {@code memberships}
 * （MEMBER / SUPPORTER）の 2 系統に分かれており、可視性 snapshot をスタブしたユニットテストでは
 * この 2 系統の合流を再現できない。スタブ化すると本番だけが壊れたまま green になるため、
 * 本テストは Testcontainers の実 MySQL に seed を投入して
 * {@link ContentVisibilityChecker} 経由の実挙動を測る。</p>
 *
 * <p><b>アサーションの方針</b>: 「ある利用者と別の利用者で判定が一致するか」ではなく、
 * 利用者ごとに<b>期待される絶対値（true / false）</b>を直接固定する。相対比較だけを置くと、
 * 双方が等しく壊れたときに偽 green になるためである。</p>
 *
 * <p><b>関連</b>: Issue #2635 / #2617（PR #2771 で {@code ALWAYS} に配信母集団の照合を追加）、
 * Issue #2780 / #2785 / #2786（組織配下の所属判定を {@code user_roles} ∪ {@code memberships} へ是正）、
 * CMP-017（認可漏れ全域監査戦役）。</p>
 */
@Transactional
@DisplayName("Issue #2774 アンケート結果 AFTER_CLOSE のスコープ所属確認")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SurveyVisibilityResolverAfterCloseScopeIT extends AbstractMySqlIntegrationTest {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ContentVisibilityChecker checker;

    @Autowired
    private OrganizationMembershipService organizationMembershipService;

    @Autowired
    private MembershipBatchQueryService membershipBatchQueryService;

    @PersistenceContext
    private EntityManager em;

    /** アンケートのスコープであるチームに在籍する一般メンバー（memberships 専属）。 */
    private Long insiderUserId;
    /** どのスコープにも所属しない利用者（他テナント相当）。 */
    private Long outsiderUserId;
    /** 当該チームを退会済み（{@code memberships.left_at IS NOT NULL}）の利用者。 */
    private Long leftUserId;
    /** 配下組織に参加するチームの一般メンバー（memberships 専属・#2780/#2785 の是正確認用）。 */
    private Long descendantUserId;
    /** プラットフォーム管理者（高速パスの陽性対照）。 */
    private Long sysAdminUserId;
    /**
     * 配下組織チームの<b>応援者</b>（{@code memberships.role_kind = SUPPORTER}）。
     *
     * <p>スコープ所属者ではあるが、{@code include_supporters = FALSE} の組織 ALL 配信の
     * <b>配信母集団には含まれない</b>。この差が {@code AFTER_CLOSE}（所属軸）と
     * {@code ALWAYS}（配信母集団軸）を区別する（Issue #2774 の検分指摘）。</p>
     */
    private Long supporterUserId;
    /** アンケートのスコープであるチームの ADMIN（設計書の優先順 2「ADMIN+ は常にフルアクセス」）。 */
    private Long scopeAdminUserId;

    private Long rootOrgId;
    private Long childOrgId;
    private Long teamId;
    private Long descendantTeamId;

    @BeforeEach
    void setUp() {
        insiderUserId = insertUser("sv2774.insider@example.com", "在籍", "太郎");
        outsiderUserId = insertUser("sv2774.outsider@example.com", "圏外", "花子");
        leftUserId = insertUser("sv2774.left@example.com", "退会", "次郎");
        descendantUserId = insertUser("sv2774.descendant@example.com", "配下", "三郎");
        sysAdminUserId = insertUser("sv2774.sysadmin@example.com", "管理", "者");
        supporterUserId = insertUser("sv2774.supporter@example.com", "応援", "四郎");
        scopeAdminUserId = insertUser("sv2774.scopeadmin@example.com", "幹部", "五郎");

        rootOrgId = insertOrganization("2774 根組織", null);
        childOrgId = insertOrganization("2774 配下組織", rootOrgId);

        teamId = insertTeam("2774 直属チーム");
        descendantTeamId = insertTeam("2774 配下チーム");
        insertTeamOrgMembership(teamId, rootOrgId);
        insertTeamOrgMembership(descendantTeamId, childOrgId);

        // 所属は memberships で表現する（V60.010 以降、MEMBER / SUPPORTER は user_roles に存在しない）。
        MembershipTestHelper.insertMembership(em, insiderUserId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, descendantUserId, ScopeType.TEAM, descendantTeamId,
                RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, leftUserId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        markMembershipLeft(leftUserId, teamId);
        MembershipTestHelper.insertMembership(em, supporterUserId, ScopeType.TEAM, descendantTeamId,
                RoleKind.SUPPORTER);

        MembershipTestHelper.insertUserRole(em, sysAdminUserId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, scopeAdminUserId, "ADMIN", teamId, null);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // 受け入れ条件 AC-1: 締切後でも、スコープに所属していない利用者は結果を閲覧できない
    // =========================================================================

    /**
     * AC-1: 明示的に締め切られた（{@code status = CLOSED}）チームアンケートについて、
     * 当該チームに所属していない利用者は結果を閲覧できない。
     */
    @Test
    @DisplayName("AC-1 締切済(CLOSED)のチームアンケートを、非所属の利用者は閲覧できない")
    void ac1_closedTeamSurveyIsNotVisibleToOutsider() {
        Long surveyId = insertTeamSurvey("2774-closed", "CLOSED", "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
    }

    /**
     * AC-1: 締切時刻を経過した（{@code status = PUBLISHED} かつ {@code expires_at} が過去）
     * チームアンケートについても、非所属の利用者は結果を閲覧できない。
     */
    @Test
    @DisplayName("AC-1 締切時刻を過ぎたチームアンケートを、非所属の利用者は閲覧できない")
    void ac1_expiredTeamSurveyIsNotVisibleToOutsider() {
        Long surveyId = insertTeamSurvey("2774-expired", "PUBLISHED", "AFTER_CLOSE",
                LocalDateTime.now().minusHours(1));
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
    }

    /**
     * AC-1: 未認証（閲覧者の識別子が無い）の場合は fail-closed で不可視である。
     * 所属を確認できない以上、締切後であっても真を返してはならない。
     */
    @Test
    @DisplayName("AC-1 締切済のアンケートを、未認証の閲覧者は閲覧できない")
    void ac1_closedSurveyIsNotVisibleToAnonymous() {
        Long surveyId = insertTeamSurvey("2774-closed-anon", "CLOSED", "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
    }

    /**
     * AC-1: 組織スコープのアンケートについても、当該組織ツリーの外に居る利用者は
     * 締切後の結果を閲覧できない。
     */
    @Test
    @DisplayName("AC-1 締切済の組織アンケートを、組織ツリー外の利用者は閲覧できない")
    void ac1_closedOrganizationSurveyIsNotVisibleToOutsider() {
        Long surveyId = insertOrganizationSurvey("2774-org-closed", rootOrgId, "CLOSED",
                "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
    }

    // =========================================================================
    // 受け入れ条件 AC-2【境界】退会済みの利用者
    // =========================================================================

    /**
     * AC-2【境界】: 当該チームを退会した（{@code memberships.left_at IS NOT NULL}）利用者は、
     * 締切後であっても結果を閲覧できない。在籍履歴ではなく<b>現在の在籍</b>で判定する。
     */
    @Test
    @DisplayName("AC-2 退会済(left_at あり)の利用者は締切後も閲覧できない")
    void ac2_leftMemberCannotViewAfterClose() {
        Long surveyId = insertTeamSurvey("2774-left", "CLOSED", "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, leftUserId)).isFalse();
    }

    // =========================================================================
    // 受け入れ条件 AC-3【陽性対照】所属している利用者は従来どおり閲覧できる
    // =========================================================================

    /**
     * AC-3【陽性対照】: 当該チームに在籍する一般メンバー（memberships 専属）は、
     * 締切後の結果を従来どおり閲覧できる。締める方向の回帰を検出するための対照である。
     */
    @Test
    @DisplayName("AC-3 在籍メンバーは締切後の結果を閲覧できる（陽性対照）")
    void ac3_insiderCanViewAfterClose() {
        Long closedId = insertTeamSurvey("2774-insider-closed", "CLOSED", "AFTER_CLOSE", null);
        Long expiredId = insertTeamSurvey("2774-insider-expired", "PUBLISHED", "AFTER_CLOSE",
                LocalDateTime.now().minusHours(1));
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, closedId, insiderUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, expiredId, insiderUserId)).isTrue();
    }

    /**
     * AC-3【陽性対照】: 配下組織に参加するチームの一般メンバー（memberships 専属）は、
     * 根組織スコープの締切済アンケートの結果を閲覧できる。
     *
     * <p>所属確認を {@code user_roles} だけで実装すると memberships 専属のメンバーを
     * 取りこぼす（Issue #2780 / #2785 / #2786 で是正した経路）。本ケースはその再発を止める。</p>
     */
    @Test
    @DisplayName("AC-3 配下組織チームの一般メンバーは組織アンケートの締切後結果を閲覧できる（陽性対照）")
    void ac3_descendantMemberCanViewOrganizationSurveyAfterClose() {
        Long surveyId = insertOrganizationSurvey("2774-org-descendant", rootOrgId, "CLOSED",
                "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, descendantUserId)).isTrue();
    }

    /**
     * AC-3【陽性対照】: プラットフォーム管理者は高速パスで従来どおり閲覧できる。
     */
    @Test
    @DisplayName("AC-3 SystemAdmin は締切後の結果を閲覧できる（陽性対照）")
    void ac3_systemAdminCanViewAfterClose() {
        Long surveyId = insertTeamSurvey("2774-sysadmin", "CLOSED", "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // 受け入れ条件 AC-4【境界】締切前・締切時刻ちょうどは従来どおりの判定
    // =========================================================================

    /**
     * AC-4【境界】: 締切前（{@code expires_at} が未来）は、在籍メンバーであっても不可視である。
     *
     * <p>判定は {@code now > expires_at} の厳密不等号であり、締切時刻ちょうどは
     * 「まだ経過していない」側に倒れる。本ケースはその未経過側の挙動を固定する。</p>
     */
    @Test
    @DisplayName("AC-4 締切前は在籍メンバーでも不可視（境界は未経過側に倒れる）")
    void ac4_beforeCloseIsInvisibleEvenForInsider() {
        Long surveyId = insertTeamSurvey("2774-future", "PUBLISHED", "AFTER_CLOSE",
                LocalDateTime.now().plusHours(1));
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
        // SystemAdmin だけは高速パスで可視（従来どおり）。
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, sysAdminUserId)).isTrue();
    }

    /**
     * AC-4【境界】: 締切未設定（{@code expires_at IS NULL}）かつ未締切は fail-closed のまま。
     */
    @Test
    @DisplayName("AC-4 締切未設定かつ未締切は fail-closed（従来どおり）")
    void ac4_nullExpiresAtStaysFailClosed() {
        Long surveyId = insertTeamSurvey("2774-null-expires", "PUBLISHED", "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
    }

    // =========================================================================
    // 受け入れ条件 AC-5【陽性対照】他の CUSTOM 値の照合が壊れていない
    // =========================================================================

    /**
     * AC-5【陽性対照】: {@code AFTER_RESPONSE} は回答履歴の照合が従来どおり効く。
     * 回答済みの在籍メンバーは可視、未回答の在籍メンバーと非所属者は不可視。
     */
    @Test
    @DisplayName("AC-5 AFTER_RESPONSE の回答履歴照合は従来どおり（陽性対照）")
    void ac5_afterResponseUnchanged() {
        Long surveyId = insertTeamSurvey("2774-after-response", "PUBLISHED", "AFTER_RESPONSE", null);
        Long questionId = insertQuestion(surveyId);
        insertResponse(surveyId, questionId, insiderUserId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, descendantUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
    }

    /**
     * AC-5【陽性対照】: {@code VIEWERS_ONLY} は結果閲覧者名簿の照合が従来どおり効く。
     * 名簿に載っていれば可視、載っていなければ在籍メンバーでも不可視。
     */
    @Test
    @DisplayName("AC-5 VIEWERS_ONLY の閲覧者名簿照合は従来どおり（陽性対照）")
    void ac5_viewersOnlyUnchanged() {
        Long surveyId = insertTeamSurvey("2774-viewers-only", "PUBLISHED", "VIEWERS_ONLY", null);
        insertResultViewer(surveyId, insiderUserId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, descendantUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, null)).isFalse();
    }

    // =========================================================================
    // 受け入れ条件 AC-6: AFTER_CLOSE の所属軸は「配信母集団」ではなく「スコープ所属」である
    //
    // 設計書 docs/features/F05.4_survey_vote.md は両者を明確に書き分けている:
    //   ALWAYS      … 「配信母集団に含まれる者」（TARGETED は名簿のみ／
    //                   include_supporters=FALSE の組織 ALL は応援者を除外）
    //   AFTER_CLOSE … 「締切後のみスコープ所属者全員」
    // 両者を同一述語に寄せると仕様より狭くなり、下記 2 者を締切後も不当に締め出す。
    // 以下 4 本の対照（AFTER_CLOSE で可視 ↔ ALWAYS で不可視）が、
    // 2 つの述語が別物であることを構造的に固定する。
    // =========================================================================

    /**
     * AC-6: TARGETED 配信のアンケートでも、締切後は<b>対象者名簿に載っていない</b>
     * スコープ所属メンバーが結果を閲覧できる。
     *
     * <p>名簿（{@code survey_targets}）は「配信母集団」の定義であって「所属」の定義ではない。
     * 締切後の基準はスコープ所属であるため、名簿の内外を問わない。</p>
     */
    @Test
    @DisplayName("AC-6 TARGETED でも名簿に無いスコープ所属メンバーは締切後に閲覧できる")
    void ac6_targetedNonRosterMemberCanViewAfterClose() {
        Long surveyId = insertSurvey("2774-targeted-after-close", "TEAM", teamId, "CLOSED",
                "AFTER_CLOSE", null, "TARGETED", false);
        em.flush();
        em.clear();

        // insider は survey_targets に一切登録していない（名簿は空）。
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isTrue();
    }

    /**
     * AC-6【陽性対照】: 同じ「名簿に無いスコープ所属メンバー」が、{@code ALWAYS} では閲覧できない。
     *
     * <p>{@code ALWAYS} の配信母集団判定（TARGETED＝名簿がそのまま母集団）が
     * 壊れていないことを固定する。AC-6 と本ケースが同時に成立することが、
     * 2 つの述語が別物である証拠になる。</p>
     */
    @Test
    @DisplayName("AC-6【陽性対照】名簿に無いスコープ所属メンバーは ALWAYS では閲覧できない")
    void ac6_targetedNonRosterMemberCannotViewAlways() {
        Long surveyId = insertSurvey("2774-targeted-always", "TEAM", teamId, "PUBLISHED",
                "ALWAYS", null, "TARGETED", false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isFalse();
    }

    /**
     * AC-6: {@code include_supporters = FALSE} の組織 ALL 配信でも、締切後は<b>応援者</b>が
     * 結果を閲覧できる。応援者は配信母集団には入らないが、スコープ所属者ではあるためである。
     */
    @Test
    @DisplayName("AC-6 include_supporters=FALSE の組織アンケートでも応援者は締切後に閲覧できる")
    void ac6_supporterCanViewOrganizationSurveyAfterClose() {
        Long surveyId = insertSurvey("2774-org-supporter-after-close", "ORGANIZATION", rootOrgId,
                "CLOSED", "AFTER_CLOSE", null, "ALL", false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, supporterUserId)).isTrue();
    }

    /**
     * AC-6【陽性対照】: 同じ応援者が、{@code ALWAYS} では閲覧できない。
     *
     * <p>{@code include_supporters = FALSE} では応援者は配信母集団に入らないため、
     * 中間集計は見せない（設計書の「配信母集団＝中間集計の閲覧母集団」）。
     * {@code ALWAYS} 側の母集団判定が壊れていないことを固定する。</p>
     */
    @Test
    @DisplayName("AC-6【陽性対照】応援者は include_supporters=FALSE の ALWAYS では閲覧できない")
    void ac6_supporterCannotViewOrganizationSurveyAlways() {
        Long surveyId = insertSurvey("2774-org-supporter-always", "ORGANIZATION", rootOrgId,
                "PUBLISHED", "ALWAYS", null, "ALL", false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, supporterUserId)).isFalse();
    }

    /**
     * AC-6【境界】: 述語を広げても、スコープ外の利用者は TARGETED / ALL いずれの配信方式でも
     * 締切後に閲覧できない。所属軸への切り替えが「誰でも可視」に戻る退行でないことを固定する。
     */
    @Test
    @DisplayName("AC-6【境界】スコープ外の利用者は配信方式によらず締切後も閲覧できない")
    void ac6_outsiderCannotViewAfterCloseRegardlessOfDistributionMode() {
        Long targetedId = insertSurvey("2774-targeted-outsider", "TEAM", teamId, "CLOSED",
                "AFTER_CLOSE", null, "TARGETED", false);
        Long allId = insertSurvey("2774-all-outsider", "TEAM", teamId, "CLOSED",
                "AFTER_CLOSE", null, "ALL", true);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, targetedId, outsiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, allId, outsiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, targetedId, null)).isFalse();
    }

    // =========================================================================
    // 受け入れ条件 AC-7: 上位条件（優先順 2 / 3）は results_visibility を無視して閲覧できる
    //
    // 設計書 docs/features/F05.4_survey_vote.md §「結果閲覧権限の判定」は
    //   1. 作成者 / 2. ADMIN+ / 3. survey_result_viewers 登録者
    // を「上位条件に該当すれば results_visibility を無視して閲覧可能」と定めている。
    // したがって AFTER_CLOSE の 2 つの軸（時間条件・所属条件）の両方を貫通しなければならない。
    // 片方だけを迂回させても AND 合成で打ち消されるため、実効性が無い。
    // =========================================================================

    /**
     * AC-7: 締切<b>前</b>の {@code AFTER_CLOSE} アンケートを、結果閲覧者名簿の登録者は閲覧できる。
     *
     * <p>優先順 3 は「{@code results_visibility} に関わらず閲覧可能」であるから、
     * 時間条件で締め出してはならない。</p>
     */
    @Test
    @DisplayName("AC-7 結果閲覧者名簿の登録者は締切前の AFTER_CLOSE を閲覧できる")
    void ac7_resultViewerCanViewBeforeClose() {
        Long surveyId = insertTeamSurvey("2774-viewer-before-close", "PUBLISHED", "AFTER_CLOSE",
                LocalDateTime.now().plusHours(1));
        insertResultViewer(surveyId, insiderUserId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isTrue();
    }

    /**
     * AC-7: 締切<b>前</b>の {@code AFTER_CLOSE} アンケートを、当該スコープの ADMIN+ は閲覧できる（優先順 2）。
     */
    @Test
    @DisplayName("AC-7 当該スコープの ADMIN+ は締切前の AFTER_CLOSE を閲覧できる")
    void ac7_scopeAdminCanViewBeforeClose() {
        Long surveyId = insertTeamSurvey("2774-admin-before-close", "PUBLISHED", "AFTER_CLOSE",
                LocalDateTime.now().plusHours(1));
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, scopeAdminUserId)).isTrue();
    }

    /**
     * AC-7【陽性対照】: 上位条件に該当しない一般所属メンバーは、締切前は閲覧できない。
     *
     * <p>上位条件の迂回が「締切前を誰にでも開ける」退行になっていないことを固定する。</p>
     */
    @Test
    @DisplayName("AC-7【陽性対照】上位条件に該当しない一般メンバーは締切前を閲覧できない")
    void ac7_plainMemberCannotViewBeforeClose() {
        Long surveyId = insertTeamSurvey("2774-plain-before-close", "PUBLISHED", "AFTER_CLOSE",
                LocalDateTime.now().plusHours(1));
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
    }

    /**
     * AC-7【境界】: {@code DRAFT}（未公開）は上位条件に該当する者でも閲覧できない。
     *
     * <p>上位条件は {@code results_visibility} を無視するが、<b>status 軸の fail-closed は無視しない</b>。
     * ここを緩めると未公開アンケートが漏れるため、境界として固定する。</p>
     */
    @Test
    @DisplayName("AC-7【境界】DRAFT は ADMIN+ / 結果閲覧者でも閲覧できない")
    void ac7_draftInvisibleEvenForPrivilegedViewers() {
        Long surveyId = insertTeamSurvey("2774-draft-privileged", "DRAFT", "AFTER_CLOSE", null);
        insertResultViewer(surveyId, insiderUserId);
        em.flush();
        em.clear();

        // 作成者は sysAdmin に固定されているため、下記はいずれも作成者ではない。
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, insiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, scopeAdminUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, surveyId, outsiderUserId)).isFalse();
    }

    /**
     * AC-7【陽性対照】: {@code ADMINS_ONLY} / {@code VIEWERS_ONLY} の既存挙動が壊れていない。
     *
     * <p>上位条件の迂回は {@code AFTER_CLOSE} の 2 軸に閉じており、他の値の意味論
     * （ロール閾値軸・名簿軸）へは波及しないことを固定する。</p>
     */
    @Test
    @DisplayName("AC-7【陽性対照】ADMINS_ONLY / VIEWERS_ONLY の既存挙動は不変")
    void ac7_adminsOnlyAndViewersOnlyUnchanged() {
        Long adminsOnlyId = insertTeamSurvey("2774-admins-only", "PUBLISHED", "ADMINS_ONLY", null);
        Long viewersOnlyId = insertTeamSurvey("2774-viewers-only-2", "PUBLISHED", "VIEWERS_ONLY", null);
        insertResultViewer(viewersOnlyId, insiderUserId);
        em.flush();
        em.clear();

        // ADMINS_ONLY: ロール閾値軸。ADMIN は可視、一般メンバー・非所属は不可視。
        assertThat(checker.canView(ReferenceType.SURVEY, adminsOnlyId, scopeAdminUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, adminsOnlyId, insiderUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, adminsOnlyId, outsiderUserId)).isFalse();

        // VIEWERS_ONLY: 名簿軸。登録者のみ可視（一般メンバー・非所属は不可視）。
        assertThat(checker.canView(ReferenceType.SURVEY, viewersOnlyId, insiderUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, viewersOnlyId, descendantUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, viewersOnlyId, outsiderUserId)).isFalse();
    }

    // =========================================================================
    // 受け入れ条件 AC-8: 所属述語を snapshot 経由へ置き換えた際の同値性と SQL 本数
    // =========================================================================

    /**
     * AC-8【同値性の実証】: 組織スコープの所属判定に用いる 2 つの述語が、実 DB 上で
     * <b>すべての被験者について同一の結果</b>を返すことを突き合わせる。
     *
     * <ul>
     *   <li>{@code OrganizationMembershipService#isUserInOrgDistributionUniverse}
     *       — 当初の実装。組織ごとに単発 EXISTS を撃つ（組織数に比例）</li>
     *   <li>{@link com.mannschaft.app.common.visibility.UserScopeRoleSnapshot#isDescendantMemberOf}
     *       — 置き換え後。複数 ORG 根を 1 本の再帰 CTE でまとめて解決する</li>
     * </ul>
     *
     * <p>置き換えの前提は「両者が同じ所属軸である」ことなので、推論ではなく実測で固定する。
     * 期待される絶対値も併せて固定し、双方が等しく壊れた場合の偽 green を防ぐ。</p>
     */
    @Test
    @DisplayName("AC-8 組織の所属判定は 2 述語で完全に一致する（同値性の実証）")
    void ac8_descendantMembershipPredicatesAgree() {
        ScopeKey orgScope = new ScopeKey("ORGANIZATION", rootOrgId);
        // 配下組織チームの一般メンバー / 応援者は所属、圏外・退会済みは非所属。
        Map<Long, Boolean> expected = new LinkedHashMap<>();
        expected.put(descendantUserId, true);
        expected.put(supporterUserId, true);
        expected.put(outsiderUserId, false);
        expected.put(leftUserId, false);

        for (Map.Entry<Long, Boolean> e : expected.entrySet()) {
            Long userId = e.getKey();
            boolean viaService =
                    organizationMembershipService.isUserInOrgDistributionUniverse(rootOrgId, userId);
            boolean viaSnapshot = membershipBatchQueryService
                    .snapshotForUser(userId, Set.of(), Set.of(), Set.of(orgScope))
                    .isDescendantMemberOf(orgScope);

            assertThat(viaService)
                    .as("service 述語の絶対値 userId=%s", userId)
                    .isEqualTo(e.getValue());
            assertThat(viaSnapshot)
                    .as("snapshot 述語の絶対値 userId=%s", userId)
                    .isEqualTo(e.getValue());
            assertThat(viaSnapshot)
                    .as("2 述語の一致 userId=%s", userId)
                    .isEqualTo(viaService);
        }
    }

    /**
     * AC-8【N+1】: 別組織のアンケートを複数まとめて {@code filterAccessible} に渡しても、
     * 組織数に比例した所属照会が発生しない。
     *
     * <p>置き換え前は組織ごとに単発 EXISTS を撃っていたため、組織数に比例して SQL が増えていた
     * （Issue #2782）。snapshot 経由では複数 ORG 根が 1 本の再帰 CTE にまとまるため、
     * 組織が増えても所属照会の本数は変わらない。</p>
     *
     * <p>ここでは「本数」を直接数える代わりに、<b>複数組織が混在しても判定が正しいこと</b>を
     * 固定する（本数そのものは Mockito でリポジトリ呼び出しを数える単体テスト側で担保する）。</p>
     */
    @Test
    @DisplayName("AC-8 別組織のアンケートを混在させても締切後の可視判定が正しい")
    void ac8_multipleOrganizationsInOneBatch() {
        Long otherOrgId = insertOrganization("2774 別組織", null);
        Long otherTeamId = insertTeam("2774 別チーム");
        insertTeamOrgMembership(otherTeamId, otherOrgId);

        Long rootSurveyId = insertOrganizationSurvey("2774-batch-root", rootOrgId, "CLOSED",
                "AFTER_CLOSE", null);
        Long otherSurveyId = insertOrganizationSurvey("2774-batch-other", otherOrgId, "CLOSED",
                "AFTER_CLOSE", null);
        em.flush();
        em.clear();

        // descendantUser は rootOrg 配下にのみ所属する。
        Set<Long> visible = checker.filterAccessible(
                ReferenceType.SURVEY, List.of(rootSurveyId, otherSurveyId), descendantUserId);

        assertThat(visible).containsExactly(rootSurveyId);
        assertThat(visible).doesNotContain(otherSurveyId);
    }

    // =========================================================================
    // seed ヘルパー
    // =========================================================================

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

    private Long insertOrganization(String name, Long parentOrganizationId) {
        String parentExpr = parentOrganizationId == null ? "NULL" : parentOrganizationId.toString();
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, parent_organization_id, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), " + parentExpr + ", NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
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
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
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

    /** 在籍中の memberships 行を退会済み（{@code left_at} 設定済み）に更新する。 */
    private void markMembershipLeft(Long userId, Long scopeId) {
        em.createNativeQuery(
                "UPDATE memberships SET left_at = NOW(), updated_at = NOW() "
                        + "WHERE user_id = :uid AND scope_type = 'TEAM' AND scope_id = :sid")
                .setParameter("uid", userId)
                .setParameter("sid", scopeId)
                .executeUpdate();
    }

    private Long insertTeamSurvey(String title, String status, String resultsVisibility,
                                  LocalDateTime expiresAt) {
        return insertSurvey(title, "TEAM", teamId, status, resultsVisibility, expiresAt);
    }

    private Long insertOrganizationSurvey(String title, Long orgId, String status,
                                          String resultsVisibility, LocalDateTime expiresAt) {
        return insertSurvey(title, "ORGANIZATION", orgId, status, resultsVisibility, expiresAt);
    }

    /**
     * surveys へ NOT NULL 全列を直接 INSERT する。
     *
     * <p>作成者は本テストのどの被験者とも異なる利用者（{@code sysAdminUserId}）に固定する。
     * 作成者本人は Service 側の高速パスで Resolver を通らないため、作成者を被験者にすると
     * 所属確認の有無を測れなくなる。</p>
     */
    private Long insertSurvey(String title, String scopeType, Long scopeId, String status,
                              String resultsVisibility, LocalDateTime expiresAt) {
        return insertSurvey(title, scopeType, scopeId, status, resultsVisibility, expiresAt,
                "ALL", false);
    }

    /**
     * 配信方式（{@code distribution_mode}）と応援者トグル（{@code include_supporters}）を
     * 明示する版。{@code AFTER_CLOSE}（所属軸）と {@code ALWAYS}（配信母集団軸）の差を測るために要る。
     */
    private Long insertSurvey(String title, String scopeType, Long scopeId, String status,
                              String resultsVisibility, LocalDateTime expiresAt,
                              String distributionMode, boolean includeSupporters) {
        String expiresExpr = expiresAt == null ? "NULL" : "'" + toDbLiteral(expiresAt) + "'";
        em.createNativeQuery(
                "INSERT INTO surveys ("
                        + "scope_type, scope_id, title, status, "
                        + "is_anonymous, allow_multiple_submissions, results_visibility, "
                        + "distribution_mode, include_supporters, unresponded_visibility, "
                        + "auto_post_to_timeline, manual_remind_count, response_count, target_count, "
                        + "version, created_by, expires_at, "
                        + "created_at, updated_at) "
                        + "VALUES (:scopeType, :scopeId, :title, :status, "
                        + "0, 0, :resultsVisibility, "
                        + ":distributionMode, :includeSupporters, 'CREATOR_AND_ADMIN', "
                        + "0, 0, 0, 0, "
                        + "0, :createdBy, " + expiresExpr + ", "
                        + "NOW(), NOW())")
                .setParameter("distributionMode", distributionMode)
                .setParameter("includeSupporters", includeSupporters ? 1 : 0)
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("title", title)
                .setParameter("status", status)
                .setParameter("resultsVisibility", resultsVisibility)
                .setParameter("createdBy", sysAdminUserId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM surveys WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    /**
     * JVM 既定ゾーン（本プロジェクトでは JST に固定）の壁時計を、DB へ直接書き込むための
     * UTC 壁時計へ変換する。
     *
     * <p>本プロジェクトは {@code spring.jpa.properties.hibernate.jdbc.time_zone = UTC} を設定しており、
     * Hibernate は {@code LocalDateTime} を UTC 基準で読み書きする。一方 native SQL のリテラルは
     * 何の変換も受けないため、JVM 既定ゾーンの壁時計をそのまま書くと、Hibernate 経由の読み出しで
     * オフセット分（JST なら 9 時間）未来にずれる。その結果「過去の締切」を意図した
     * フィクスチャが「未来の締切」として読まれ、時間条件が成立しないまま
     * <b>fail-closed の false を「所属確認が効いた false」と取り違える</b>偽陰性になる。
     * 締切前後の判定を測るテストでは、この変換を必ず通すこと。</p>
     */
    private static String toDbLiteral(LocalDateTime jvmLocal) {
        return LocalDateTime.ofInstant(
                jvmLocal.atZone(ZoneId.systemDefault()).toInstant(), ZoneOffset.UTC).format(DT_FMT);
    }

    private void insertResultViewer(Long surveyId, Long userId) {
        em.createNativeQuery(
                "INSERT INTO survey_result_viewers (survey_id, user_id, created_at) "
                        + "VALUES (:sid, :uid, NOW())")
                .setParameter("sid", surveyId)
                .setParameter("uid", userId)
                .executeUpdate();
    }

    private Long insertQuestion(Long surveyId) {
        em.createNativeQuery(
                "INSERT INTO survey_questions ("
                        + "survey_id, question_text, question_type, "
                        + "is_required, display_order, created_at) "
                        + "VALUES (:sid, '質問', 'FREE_TEXT', 0, 0, NOW())")
                .setParameter("sid", surveyId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM survey_questions WHERE survey_id = :sid ORDER BY id DESC LIMIT 1")
                .setParameter("sid", surveyId)
                .getSingleResult()).longValue();
    }

    private void insertResponse(Long surveyId, Long questionId, Long userId) {
        em.createNativeQuery(
                "INSERT INTO survey_responses (survey_id, question_id, user_id, text_response, "
                        + "created_at, updated_at) "
                        + "VALUES (:sid, :qid, :uid, '回答テキスト', NOW(), NOW())")
                .setParameter("sid", surveyId)
                .setParameter("qid", questionId)
                .setParameter("uid", userId)
                .executeUpdate();
    }
}
