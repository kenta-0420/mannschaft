package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 試練（#2617-3）— {@code ResultsVisibility.ALWAYS} の可視性判定契約テスト。
 *
 * <p>設計書 {@code docs/features/F05.4_survey_vote.md}: {@code ALWAYS} は
 * 「{@code PUBLISHED} になった時点から締切前も含め、<b>配信対象スコープの会員全員</b>が
 * 中間集計を閲覧できる」設定である。すなわち {@code AFTER_CLOSE} から時間制約を外し、
 * かつスコープ会員という所属条件は残したもの。</p>
 *
 * <p>実装前は {@code ResultsVisibility.valueOf("ALWAYS")} が
 * {@link IllegalArgumentException} を投げて red となる。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-8 / AC-9 / AC-10 / AC-11</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SurveyVisibilityResolver — ALWAYS の判定（#2617-3）")
class SurveyVisibilityResolverAlwaysTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private SurveyRepository surveyRepository;

    @Mock
    private SurveyResponseRepository surveyResponseRepository;

    @Mock
    private SurveyResultViewerRepository surveyResultViewerRepository;

    @Mock
    private SurveyTargetRepository surveyTargetRepository;

    private SurveyVisibilityResolver resolver;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 100L;
    private static final Long SURVEY_ID = 1L;
    private static final Long AUTHOR_ID = 99L;
    private static final Long VIEWER_ID = 5L;
    private static final Long ORG_ID = 700L;

    @BeforeEach
    void setUp() {
        resolver = new SurveyVisibilityResolver(
                membershipBatchQueryService,
                templateEvaluator,
                new VisibilityMetrics(new SimpleMeterRegistry()),
                null,
                auditLogService,
                surveyRepository,
                surveyResponseRepository,
                surveyResultViewerRepository,
                surveyTargetRepository);
    }

    @Nested
    @DisplayName("ALWAYS — 公開直後から会員全員が閲覧可")
    class Always {

        /**
         * AC-8 — {@code PUBLISHED} かつ<b>締切前</b>でもスコープ会員は結果を閲覧できる。
         * （{@code AFTER_CLOSE} との差分そのもの）
         */
        @Test
        @DisplayName("AC-8: PUBLISHED かつ締切前でもスコープ会員には可視")
        void ac8_visibleToScopeMemberBeforeDeadline() {
            stubProjection(SurveyStatus.PUBLISHED, always(), LocalDateTime.now().plusDays(7));
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-8: ALWAYS は締切前でも会員に中間集計を見せる")
                    .isTrue();
        }

        /**
         * AC-9 — 認可。スコープ外のユーザー（他テナント・非会員・未認証）には見せない。
         */
        @Test
        @DisplayName("AC-9: 非会員には不可視")
        void ac9_invisibleToNonMember() {
            stubProjection(SurveyStatus.PUBLISHED, always(), LocalDateTime.now().plusDays(7));
            stubSnapshot(VIEWER_ID, UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-9: 配信スコープ外のユーザーに中間集計を渡してはならない")
                    .isFalse();
        }

        @Test
        @DisplayName("AC-9: 他スコープの会員には不可視（別テナント）")
        void ac9_invisibleToOtherScopeMember() {
            stubProjection(SurveyStatus.PUBLISHED, always(), LocalDateTime.now().plusDays(7));
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(new ScopeKey(SCOPE_TYPE, 999L), "ADMIN"),
                    Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("AC-9: 未認証（userId=null）は fail-closed")
        void ac9_anonymousFailClosed() {
            stubProjection(SurveyStatus.PUBLISHED, always(), LocalDateTime.now().plusDays(7));
            stubSnapshot(null, UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(SURVEY_ID, null)).isFalse();
        }

        /**
         * AC-10 — 境界（公開前）。{@code DRAFT} では会員であっても閲覧できない。
         */
        @Test
        @DisplayName("AC-10: DRAFT（未公開）は会員にも不可視")
        void ac10_invisibleWhileDraft() {
            stubProjection(SurveyStatus.DRAFT, always(), null);
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-10: ALWAYS でも公開前は status 軸で弾かれる")
                    .isFalse();
        }

        @Test
        @DisplayName("AC-8: CLOSED（締切後）も引き続き会員に可視")
        void ac8_visibleAfterClose() {
            stubProjection(SurveyStatus.CLOSED, always(), LocalDateTime.now().minusDays(1));
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-11: 既存4値の判定は従来どおり（回帰防止）")
    class ExistingValuesRegression {

        @Test
        @DisplayName("AC-11: AFTER_RESPONSE は回答済みのみ（会員でも未回答なら不可視）")
        void ac11_afterResponseOnlyForRespondents() {
            stubProjection(SurveyStatus.PUBLISHED, ResultsVisibility.AFTER_RESPONSE, null);
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));

            when(surveyResponseRepository.existsBySurveyIdAndUserId(eq(SURVEY_ID), eq(VIEWER_ID)))
                    .thenReturn(false);
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isFalse();

            when(surveyResponseRepository.existsBySurveyIdAndUserId(eq(SURVEY_ID), eq(VIEWER_ID)))
                    .thenReturn(true);
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("AC-11: AFTER_CLOSE は締切後のみ（締切前は会員でも不可視）")
        void ac11_afterCloseOnlyAfterDeadline() {
            stubProjection(SurveyStatus.PUBLISHED, ResultsVisibility.AFTER_CLOSE,
                    LocalDateTime.now().plusDays(1));
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isFalse();

            stubProjection(SurveyStatus.PUBLISHED, ResultsVisibility.AFTER_CLOSE,
                    LocalDateTime.now().minusDays(1));
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("AC-11: ADMINS_ONLY はロール閾値（ADMIN は可視・MEMBER は不可視）")
        void ac11_adminsOnlyIsRoleThreshold() {
            stubProjection(SurveyStatus.PUBLISHED, ResultsVisibility.ADMINS_ONLY, null);

            stubSnapshot(VIEWER_ID, roleInScope("ADMIN"));
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isTrue();

            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isFalse();
        }

        /**
         * AC-11 — {@code VIEWERS_ONLY} は名簿判定であり、ロール閾値ではない。
         * <b>名簿に無い ADMIN が見えないこと</b>を固定する（FE で ADMINS_ONLY と畳んではならない根拠）。
         */
        @Test
        @DisplayName("AC-11: VIEWERS_ONLY は名簿判定（名簿に無い ADMIN は不可視）")
        void ac11_viewersOnlyIsRosterNotRole() {
            stubProjection(SurveyStatus.PUBLISHED, ResultsVisibility.VIEWERS_ONLY, null);
            stubSnapshot(VIEWER_ID, roleInScope("ADMIN"));

            when(surveyResultViewerRepository.existsBySurveyIdAndUserId(eq(SURVEY_ID), eq(VIEWER_ID)))
                    .thenReturn(false);
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-11: ADMIN であっても名簿に無ければ不可視（ADMINS_ONLY とは直交）")
                    .isFalse();

            when(surveyResultViewerRepository.existsBySurveyIdAndUserId(eq(SURVEY_ID), eq(VIEWER_ID)))
                    .thenReturn(true);
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isTrue();
        }
    }

    /**
     * 検分指摘（P1）— ORGANIZATION × ALL 配信の「配信母集団＝閲覧母集団」不変条件。
     *
     * <p>設計書 {@code docs/features/F05.4_survey_vote.md} の {@code distribution_mode} 備考:
     * 組織スコープで {@code ALL} を選ぶと母集団は「組織直属メンバー ∪ 配下参加チーム（ACTIVE）の
     * メンバー」まで再帰展開される。可視性が直接所属（{@code SCOPE_AFFILIATED}）のままだと
     * 「アンケートは届くのに ALWAYS の結果だけ 403」という食い違いが生じるため、
     * 下向き再帰の既存軸 {@code ORGANIZATION_AND_DESCENDANTS} へ昇格させる。</p>
     *
     * <p>担保する受け入れ条件: <b>AC-18 / AC-19 / AC-20</b>。</p>
     */
    @Nested
    @DisplayName("AC-18〜20: ORGANIZATION × ALL の配信母集団と可視範囲の一致")
    class OrganizationDistributionUniverse {

        /**
         * AC-18 — 配下 ACTIVE チームのメンバー（組織には直属していない）が結果を閲覧できる。
         * 昇格前は {@code isMemberOf} が false のため不可視となり red。
         */
        @Test
        @DisplayName("AC-18: 配下ACTIVEチームのメンバーは ALWAYS の結果を閲覧できる")
        void ac18_descendantTeamMemberCanView() {
            stubOrgProjection(SurveyStatus.PUBLISHED, always(), false);
            // 組織への直接所属は無い（roleByScope 空）。配下ツリーの実効ロールのみ MEMBER。
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(ORG_ID), Map.of(), Map.of(ORG_ID, "MEMBER")));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-18: 配信されているのに結果だけ 403 になってはならない")
                    .isTrue();
        }

        /**
         * AC-19 — 認可が緩んでいないことの裏取り。
         * 配下チームにも組織にも属さないユーザーは依然として不可視。
         */
        @Test
        @DisplayName("AC-19: 配下でも組織でもないユーザーは依然として不可視")
        void ac19_outsiderStillCannotView() {
            stubOrgProjection(SurveyStatus.PUBLISHED, always(), false);
            stubSnapshot(VIEWER_ID, UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-19: 下向き再帰へ開いた副作用でスコープ外に穴を開けてはならない")
                    .isFalse();

            // 別テナント（他組織）の配下メンバーであっても当該組織の結果は見えない。
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(999L), Map.of(), Map.of(999L, "ADMIN")));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-19: 他組織ツリーの所属は当該組織の可視性を与えない")
                    .isFalse();
        }

        /**
         * AC-20 — {@code includeSupporters = false}（既定）の配信では応援者は母集団に入らないため、
         * 中間集計も見せない。所属軸は SUPPORTER を含む（G7）ので、追加軸で MEMBER 閾値を課している。
         */
        @Test
        @DisplayName("AC-20: includeSupporters=false のとき応援者は ALWAYS の結果を閲覧できない")
        void ac20_supporterCannotViewWhenExcludedFromDistribution() {
            stubOrgProjection(SurveyStatus.PUBLISHED, always(), false);
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(ORG_ID), Map.of(), Map.of(ORG_ID, "SUPPORTER")));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-20: 配信されていない応援者に中間集計を見せてはならない")
                    .isFalse();
        }

        /**
         * AC-20（陽性対照）— {@code includeSupporters = true} なら応援者も配信母集団に入るため閲覧可。
         * 「常に false」で AC-20 を通す実装を弾く。
         */
        @Test
        @DisplayName("AC-20: includeSupporters=true なら応援者も閲覧できる（陽性対照）")
        void ac20_supporterCanViewWhenIncludedInDistribution() {
            stubOrgProjection(SurveyStatus.PUBLISHED, always(), true);
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(ORG_ID), Map.of(), Map.of(ORG_ID, "SUPPORTER")));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-20: 配信対象に含めた応援者は閲覧できること（母集団と一致）")
                    .isTrue();
        }

        /** AC-10 の組織版 — 昇格しても DRAFT は status 軸で弾かれ続ける。 */
        @Test
        @DisplayName("AC-18(境界): 組織スコープでも DRAFT は配下メンバーに不可視")
        void ac18_draftStillInvisibleForDescendant() {
            stubOrgProjection(SurveyStatus.DRAFT, always(), false);
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(ORG_ID), Map.of(), Map.of(ORG_ID, "MEMBER")));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isFalse();
        }

        /** 既存 4 値は昇格の対象外（ALWAYS 固有の是正であることの裏取り）。 */
        @Test
        @DisplayName("AC-11(補): AFTER_CLOSE は昇格せず従来どおり（配下メンバーには不可視）")
        void ac11_afterCloseIsNotPromoted() {
            stubOrgProjection(SurveyStatus.PUBLISHED, ResultsVisibility.ADMINS_ONLY, false);
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(ORG_ID), Map.of(), Map.of(ORG_ID, "MEMBER")));

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("ALWAYS 以外の値の判定は一切変えない")
                    .isFalse();
        }
    }

    /**
     * 検分指摘（P1・2度目）— TARGETED 配信では対象者名簿が配信母集団そのものである。
     *
     * <p>{@code ALWAYS} を所属軸だけで判定すると、名簿に載っていない同一スコープの所属者にまで
     * 中間集計が漏れる。設計書の「配信母集団＝閲覧母集団」に合わせ、名簿登録を追加軸で必須にする。</p>
     *
     * <p>担保する受け入れ条件: <b>AC-21 / AC-22</b>。</p>
     */
    @Nested
    @DisplayName("AC-21〜22: TARGETED 配信は対象者名簿を必須にする")
    class TargetedDistributionRoster {

        /** AC-21 — スコープ所属者であっても名簿外なら不可視（漏洩の根治）。 */
        @Test
        @DisplayName("AC-21: 名簿外のスコープ所属者は ALWAYS の結果を閲覧できない")
        void ac21_nonTargetScopeMemberCannotView() {
            stubTargetedProjection(SCOPE_TYPE, SCOPE_ID, SurveyStatus.PUBLISHED, always());
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));
            when(surveyTargetRepository.existsBySurveyIdAndUserId(eq(SURVEY_ID), eq(VIEWER_ID)))
                    .thenReturn(false);

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-21: 配信されていない所属者に中間集計を渡してはならない")
                    .isFalse();
        }

        /** AC-22 — 名簿登録者は閲覧できる（陽性対照。「常に false」実装を弾く）。 */
        @Test
        @DisplayName("AC-22: 名簿登録者は ALWAYS の結果を閲覧できる（陽性対照）")
        void ac22_targetUserCanView() {
            stubTargetedProjection(SCOPE_TYPE, SCOPE_ID, SurveyStatus.PUBLISHED, always());
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));
            when(surveyTargetRepository.existsBySurveyIdAndUserId(eq(SURVEY_ID), eq(VIEWER_ID)))
                    .thenReturn(true);

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-22: 配信対象者は中間集計を閲覧できること")
                    .isTrue();
        }

        /** AC-21（組織版）— 昇格した下向き再帰軸でも名簿外は通さない。 */
        @Test
        @DisplayName("AC-21: 組織スコープでも名簿外の配下メンバーは不可視")
        void ac21_orgDescendantWithoutRosterCannotView() {
            stubTargetedProjection("ORGANIZATION", ORG_ID, SurveyStatus.PUBLISHED, always());
            stubSnapshot(VIEWER_ID, new UserScopeRoleSnapshot(false,
                    Map.of(), Map.of(), Set.of(), Set.of(),
                    Set.of(ORG_ID), Map.of(), Map.of(ORG_ID, "MEMBER")));
            when(surveyTargetRepository.existsBySurveyIdAndUserId(eq(SURVEY_ID), eq(VIEWER_ID)))
                    .thenReturn(false);

            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID)).isFalse();
        }

        /** 未認証は名簿照合以前に fail-closed。 */
        @Test
        @DisplayName("AC-21: 未認証（userId=null）は TARGETED でも fail-closed")
        void ac21_anonymousFailClosed() {
            stubTargetedProjection(SCOPE_TYPE, SCOPE_ID, SurveyStatus.PUBLISHED, always());
            stubSnapshot(null, UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(SURVEY_ID, null)).isFalse();
        }

        /**
         * AC-23 — ALL 配信の既存挙動が変わっていないこと。
         * 名簿を一切引かずに（＝名簿判定へ落ちずに）所属軸だけで通ることを stub 無しで確認する。
         */
        @Test
        @DisplayName("AC-23: ALL 配信は名簿を参照せず従来どおり所属者に可視")
        void ac23_allModeUnaffectedByRosterGate() {
            stubProjection(SurveyStatus.PUBLISHED, always(), LocalDateTime.now().plusDays(7));
            stubSnapshot(VIEWER_ID, roleInScope("MEMBER"));

            // surveyTargetRepository は未 stub（既定 false）。ここで true になるなら
            // ALL 経路が名簿判定へ落ちていない証拠。
            assertThat(resolver.canView(SURVEY_ID, VIEWER_ID))
                    .as("AC-23: ALL 配信の判定に名簿ゲートを混ぜてはならない")
                    .isTrue();
            org.mockito.Mockito.verify(surveyTargetRepository, org.mockito.Mockito.never())
                    .existsBySurveyIdAndUserId(org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());
        }
    }

    // ───────────────────────── ヘルパ ─────────────────────────

    /** 実装前はここで {@link IllegalArgumentException} が投げられ red となる。 */
    private static ResultsVisibility always() {
        return ResultsVisibility.valueOf("ALWAYS");
    }

    private void stubProjection(SurveyStatus status, ResultsVisibility visibility, LocalDateTime expiresAt) {
        when(surveyRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(new SurveyVisibilityProjection(
                        SURVEY_ID, SCOPE_TYPE, SCOPE_ID, AUTHOR_ID, status, visibility, expiresAt,
                        false, DistributionMode.ALL)));
    }

    /** ORGANIZATION スコープ × ALL 配信の Projection（AC-18〜20 用）。 */
    private void stubOrgProjection(SurveyStatus status, ResultsVisibility visibility,
                                   boolean includeSupporters) {
        when(surveyRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(new SurveyVisibilityProjection(
                        SURVEY_ID, "ORGANIZATION", ORG_ID, AUTHOR_ID, status, visibility, null,
                        includeSupporters, DistributionMode.ALL)));
    }

    /** TARGETED 配信の Projection（AC-21/22 用）。 */
    private void stubTargetedProjection(String scopeType, Long scopeId,
                                        SurveyStatus status, ResultsVisibility visibility) {
        when(surveyRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(new SurveyVisibilityProjection(
                        SURVEY_ID, scopeType, scopeId, AUTHOR_ID, status, visibility, null,
                        false, DistributionMode.TARGETED)));
    }

    /**
     * snapshot をスタブする。
     *
     * <p>3 引数版（direct / orgWide）と 4 引数版（＋ descendant）の<b>両方</b>を stub する。
     * ORGANIZATION_AND_DESCENDANTS へ昇格した row があると
     * {@code AbstractContentVisibilityResolver} は descendantScopes を伴う 4 引数版を呼ぶため、
     * 3 引数版だけを stub すると snapshot が null になり NPE で落ちる
     * （＝昇格が snapshot 取得<b>前</b>に効いていることの裏返しでもある）。</p>
     */
    private void stubSnapshot(Long userId, UserScopeRoleSnapshot snapshot) {
        if (userId == null) {
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(snapshot);
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet(), anySet()))
                    .thenReturn(snapshot);
        } else {
            when(membershipBatchQueryService.snapshotForUser(eq(userId), anySet(), anySet()))
                    .thenReturn(snapshot);
            when(membershipBatchQueryService.snapshotForUser(eq(userId), anySet(), anySet(), anySet()))
                    .thenReturn(snapshot);
        }
    }

    private static UserScopeRoleSnapshot roleInScope(String role) {
        return new UserScopeRoleSnapshot(false,
                Map.of(new ScopeKey(SCOPE_TYPE, SCOPE_ID), role),
                Map.of(), Set.of(), Set.of());
    }
}
