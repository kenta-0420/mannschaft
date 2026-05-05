package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F00 Phase C — {@link SurveyVisibilityResolver} 単体テスト。
 *
 * <p>Repository 群と {@link MembershipBatchQueryService} をモック化し、CUSTOM 3 値
 * (AFTER_RESPONSE / AFTER_CLOSE / VIEWERS_ONLY) を中心に Survey 固有の判定が
 * 設計書 §5.1.4 / §7.5 / §11.6 と整合することを網羅検証する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス・親 ORG 連鎖）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * Survey 固有の正規化（CUSTOM 経路 / SurveyStatus → ContentStatus）に焦点を当てる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyVisibilityResolver — 単体テスト")
class SurveyVisibilityResolverTest {

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

    private VisibilityMetrics visibilityMetrics;
    private SurveyVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new SurveyVisibilityResolver(
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                null,            // FollowBatchService 不要
                auditLogService,
                surveyRepository,
                surveyResponseRepository,
                surveyResultViewerRepository);
    }

    @Test
    @DisplayName("referenceType() は SURVEY を返す")
    void referenceType_is_SURVEY() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.SURVEY);
    }

    // -------------------------------------------------------------------------
    // 入口ガード
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("入口ガード")
    class EntryGuard {

        @Test
        @DisplayName("contentId=null は false")
        void canView_nullId_false() {
            assertThat(resolver.canView(null, 1L)).isFalse();
            verifyNoInteractions(surveyRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(surveyRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false")
        void canView_unknownId_false() {
            when(surveyRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
                    .thenReturn(List.of());

            assertThat(resolver.canView(99L, 1L)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
        }
    }

    // -------------------------------------------------------------------------
    // ADMINS_ONLY 経路（Mapper 直結 — CUSTOM ではない）
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ADMINS_ONLY 経路")
    class AdminsOnly {

        @Test
        @DisplayName("ADMINS_ONLY × PUBLISHED は ADMIN ロール所持者に可視")
        void admins_only_visible_to_admin() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.ADMINS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "ADMIN"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("ADMINS_ONLY × PUBLISHED は MEMBER には不可視")
        void admins_only_invisible_to_member() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.ADMINS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isFalse();
            // CUSTOM 経路を通っていないことを確認（Mapper 直結）
            verify(surveyResponseRepository, never()).existsBySurveyIdAndUserId(any(), any());
            verify(surveyResultViewerRepository, never()).existsBySurveyIdAndUserId(any(), any());
        }
    }

    // -------------------------------------------------------------------------
    // CUSTOM: AFTER_RESPONSE
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CUSTOM — AFTER_RESPONSE")
    class AfterResponse {

        @Test
        @DisplayName("回答済みユーザーには可視")
        void responded_visible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_RESPONSE, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(surveyResponseRepository.existsBySurveyIdAndUserId(eq(1L), eq(5L)))
                    .thenReturn(true);

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("未回答ユーザーには不可視")
        void not_responded_invisible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_RESPONSE, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(surveyResponseRepository.existsBySurveyIdAndUserId(eq(1L), eq(5L)))
                    .thenReturn(false);

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("匿名ユーザー (userId=null) は fail-closed (false)")
        void anonymous_fail_closed() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_RESPONSE, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
            // 匿名なら DB 照会自体を行わない
            verify(surveyResponseRepository, never()).existsBySurveyIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("SystemAdmin は AFTER_RESPONSE でも常に可視（status ガード通過後の高速パス）")
        void system_admin_visible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_RESPONSE, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
            // SystemAdmin 高速パスにより CUSTOM 評価自体スキップされる
            verify(surveyResponseRepository, never()).existsBySurveyIdAndUserId(any(), any());
        }
    }

    // -------------------------------------------------------------------------
    // CUSTOM: AFTER_CLOSE
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CUSTOM — AFTER_CLOSE")
    class AfterClose {

        @Test
        @DisplayName("expiresAt が過去なら可視")
        void expired_visible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.CLOSED,
                    ResultsVisibility.AFTER_CLOSE,
                    LocalDateTime.now().minusHours(1));
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isTrue();
            // 匿名でも締切後なら可視
            assertThat(resolver.canView(1L, null)).isTrue();
        }

        @Test
        @DisplayName("expiresAt が未来なら不可視")
        void not_yet_expired_invisible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_CLOSE,
                    LocalDateTime.now().plusHours(1));
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("expiresAt = null（締切未設定）は fail-closed（軍議裁可）")
        void no_expires_at_fail_closed() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_CLOSE, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin は AFTER_CLOSE でも常に可視（高速パス）")
        void system_admin_visible_even_before_close() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_CLOSE,
                    LocalDateTime.now().plusHours(1));
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // CUSTOM: VIEWERS_ONLY
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("CUSTOM — VIEWERS_ONLY")
    class ViewersOnly {

        @Test
        @DisplayName("survey_result_viewers に登録ユーザーは可視")
        void registered_viewer_visible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.VIEWERS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(surveyResultViewerRepository.existsBySurveyIdAndUserId(eq(1L), eq(5L)))
                    .thenReturn(true);

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("未登録ユーザーは不可視")
        void unregistered_invisible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.VIEWERS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(surveyResultViewerRepository.existsBySurveyIdAndUserId(eq(1L), eq(5L)))
                    .thenReturn(false);

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("匿名ユーザー (userId=null) は fail-closed")
        void anonymous_fail_closed() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.VIEWERS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
            verify(surveyResultViewerRepository, never()).existsBySurveyIdAndUserId(any(), any());
        }

        @Test
        @DisplayName("SystemAdmin は VIEWERS_ONLY でも常に可視（高速パス）")
        void system_admin_visible() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.VIEWERS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(any(), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 5L)).isTrue();
            verify(surveyResultViewerRepository, never()).existsBySurveyIdAndUserId(any(), any());
        }
    }

    // -------------------------------------------------------------------------
    // status 軸ガード
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("status 軸ガード（§7.5）")
    class StatusGuard {

        @Test
        @Disabled("Phase D で AbstractContentVisibilityResolver を根治後に有効化する。"
                + "現行 Abstract は status × visibility AND 条件のため、DRAFT × ADMINS_ONLY で"
                + "作者本人でも visibility 軸 (ADMIN ロール要) で弾かれる。"
                + "設計書 §7.5「DRAFT は作成者本人および SystemAdmin のみ閲覧可」(visibility 軸スキップ)"
                + "の本来仕様を実装する根治は Phase D 別軍議で実施予定。"
                + "メモリ: project_f00_phase_d_open_questions.md 参照。")
        @DisplayName("DRAFT は作成者本人に可視（visibility=ADMINS_ONLY であっても）")
        void draft_visible_to_author() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 5L, SurveyStatus.DRAFT,
                    ResultsVisibility.ADMINS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }

        @Test
        @DisplayName("DRAFT は作成者以外の MEMBER には不可視")
        void draft_invisible_to_member() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.DRAFT,
                    ResultsVisibility.ADMINS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isFalse();
        }

        @Test
        @DisplayName("ARCHIVED は SystemAdmin のみ可視")
        void archived_visible_to_sysadmin_only() {
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 5L, SurveyStatus.ARCHIVED,
                    ResultsVisibility.ADMINS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, 5L)).isFalse();

            when(membershipBatchQueryService.snapshotForUser(eq(99L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());
            assertThat(resolver.canView(1L, 99L)).isTrue();
        }

        @Test
        @DisplayName("CLOSED は PUBLISHED 相当として visibility 評価へ進む")
        void closed_treated_as_published() {
            // CLOSED + ADMINS_ONLY × ADMIN → 可視
            SurveyVisibilityProjection p = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.CLOSED,
                    ResultsVisibility.ADMINS_ONLY, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "ADMIN"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(1L, 5L)).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // バッチ呼び出し
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("バッチ呼び出し")
    class Batch {

        @Test
        @DisplayName("filterAccessible は Repository を 1 回・Membership を 1 回呼ぶ")
        void single_call_for_batch() {
            SurveyVisibilityProjection p1 = projection(
                    1L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.ADMINS_ONLY, null);
            SurveyVisibilityProjection p2 = projection(
                    2L, "TEAM", 100L, 99L, SurveyStatus.PUBLISHED,
                    ResultsVisibility.AFTER_RESPONSE, null);
            when(surveyRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(p1, p2));
            when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", 100L), "ADMIN"),
                            Map.of(), Set.of(), Set.of()));
            when(surveyResponseRepository.existsBySurveyIdAndUserId(eq(2L), eq(5L)))
                    .thenReturn(true);

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 5L);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
            verify(surveyRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(5L), anySet(), anySet());
        }
    }

    // -------------------------------------------------------------------------
    // ヘルパ
    // -------------------------------------------------------------------------

    private static SurveyVisibilityProjection projection(
            Long id, String scopeType, Long scopeId, Long authorUserId,
            SurveyStatus status, ResultsVisibility visibility, LocalDateTime expiresAt) {
        return new SurveyVisibilityProjection(
                id, scopeType, scopeId, authorUserId, status, visibility, expiresAt);
    }
}
