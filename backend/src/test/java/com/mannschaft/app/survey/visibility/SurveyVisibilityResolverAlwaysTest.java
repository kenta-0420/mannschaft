package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
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

    private SurveyVisibilityResolver resolver;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 100L;
    private static final Long SURVEY_ID = 1L;
    private static final Long AUTHOR_ID = 99L;
    private static final Long VIEWER_ID = 5L;

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
                surveyResultViewerRepository);
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

    // ───────────────────────── ヘルパ ─────────────────────────

    /** 実装前はここで {@link IllegalArgumentException} が投げられ red となる。 */
    private static ResultsVisibility always() {
        return ResultsVisibility.valueOf("ALWAYS");
    }

    private void stubProjection(SurveyStatus status, ResultsVisibility visibility, LocalDateTime expiresAt) {
        when(surveyRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(new SurveyVisibilityProjection(
                        SURVEY_ID, SCOPE_TYPE, SCOPE_ID, AUTHOR_ID, status, visibility, expiresAt)));
    }

    private void stubSnapshot(Long userId, UserScopeRoleSnapshot snapshot) {
        if (userId == null) {
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(snapshot);
        } else {
            when(membershipBatchQueryService.snapshotForUser(eq(userId), anySet(), anySet()))
                    .thenReturn(snapshot);
        }
    }

    private static UserScopeRoleSnapshot roleInScope(String role) {
        return new UserScopeRoleSnapshot(false,
                Map.of(new ScopeKey(SCOPE_TYPE, SCOPE_ID), role),
                Map.of(), Set.of(), Set.of());
    }
}
