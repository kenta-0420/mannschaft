package com.mannschaft.app.social.announcement.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link AnnouncementFeedVisibilityResolver} の単体テスト（F02.6 / F08.9 P4b）。
 *
 * <p>以下を検証する:</p>
 * <ul>
 *   <li>{@link ReferenceType#ANNOUNCEMENT_FEED} を返す</li>
 *   <li>ロール軸（PUBLIC / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE）の判定が
 *       既存ロジックと同一であること</li>
 *   <li>ペイウォール軸（CUSTOM）の判定: {@link PaymentGateService#checkAccess} が呼ばれること</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementFeedVisibilityResolver — F02.6 / F08.9 P4b")
class AnnouncementFeedVisibilityResolverTest {

    @Mock
    private AnnouncementFeedRepository announcementFeedRepository;
    @Mock
    private PaymentGateService paymentGateService;
    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;
    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;
    @Mock
    private FollowBatchService followBatchService;
    @Mock
    private AuditLogService auditLogService;

    private VisibilityMetrics visibilityMetrics;
    private AnnouncementFeedVisibilityResolver resolver;

    private static final Long TEAM_ID = 100L;
    private static final Long FEED_ID = 10L;
    private static final Long SOURCE_ID = 50L;
    private static final Long VIEWER_ID = 2L;
    private static final Long ADMIN_ID = 99L;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new AnnouncementFeedVisibilityResolver(
                announcementFeedRepository,
                paymentGateService,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                followBatchService,
                auditLogService);
    }

    /**
     * テスト用 Projection を生成するヘルパ。
     */
    private AnnouncementFeedVisibilityProjection projection(
            Long id,
            AnnouncementFeedVisibility visibility,
            String sourceType,
            Long sourceId) {
        return new AnnouncementFeedVisibilityProjection(
                id, "TEAM", TEAM_ID, null, null,
                visibility, sourceType, sourceId);
    }

    // ========================================================================
    // referenceType
    // ========================================================================

    @Test
    @DisplayName("referenceType() は ANNOUNCEMENT_FEED を返す")
    void referenceType_announcementFeed() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.ANNOUNCEMENT_FEED);
    }

    // ========================================================================
    // ロール軸の判定
    // ========================================================================

    @Nested
    @DisplayName("ロール軸の判定（既存 AnnouncementVisibility ロジックと同一セマンティクス）")
    class RoleBasedVisibility {

        @Test
        @DisplayName("PUBLIC は未認証ユーザーでも可視")
        void public_anonymous_allowed() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.PUBLIC, null, null);
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, null)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_AND_ABOVE + MEMBER → 可視")
        void membersAndAbove_member_allowed() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.MEMBERS_AND_ABOVE, null, null);
            UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                    false,
                    Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                    Map.of(), Set.of(), Set.of());
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(snapshot);

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("MEMBERS_AND_ABOVE + SUPPORTER → 不可視（内輪）")
        void membersAndAbove_supporter_denied() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.MEMBERS_AND_ABOVE, null, null);
            // SUPPORTER ロールは MEMBERS_AND_ABOVE より低い（hasRoleOrAbove(MEMBER) = false）
            UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                    false,
                    Map.of(new ScopeKey("TEAM", TEAM_ID), "SUPPORTER"),
                    Map.of(), Set.of(), Set.of());
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(snapshot);

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("SUPPORTERS_AND_ABOVE + SUPPORTER → 可視")
        void supportersAndAbove_supporter_allowed() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.SUPPORTERS_AND_ABOVE, null, null);
            UserScopeRoleSnapshot snapshot = new UserScopeRoleSnapshot(
                    false,
                    Map.of(new ScopeKey("TEAM", TEAM_ID), "SUPPORTER"),
                    Map.of(), Set.of(), Set.of());
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(snapshot);

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, VIEWER_ID)).isTrue();
        }
    }

    // ========================================================================
    // F08.9 P4b — CUSTOM visibility（ペイウォール）判定
    // ========================================================================

    @Nested
    @DisplayName("F08.9 P4b — CUSTOM visibility（ペイウォール）判定")
    class PaywallEvaluateCustom {

        @Test
        @DisplayName("ペイウォールあり + 支払い済み → 閲覧可")
        void paywall_paid_accessible() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.CUSTOM,
                    AnnouncementSourceType.BLOG_POST.name(), SOURCE_ID);
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(paymentGateService.checkAccess(
                    eq(AnnouncementSourceType.BLOG_POST.name()), eq(SOURCE_ID), eq(VIEWER_ID)))
                    .thenReturn(new GateCheckResponse(true, false, List.of()));

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, VIEWER_ID)).isTrue();
        }

        @Test
        @DisplayName("ペイウォールあり + 未払い → 閲覧不可")
        void paywall_unpaid_denied() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.CUSTOM,
                    AnnouncementSourceType.BLOG_POST.name(), SOURCE_ID);
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(paymentGateService.checkAccess(
                    eq(AnnouncementSourceType.BLOG_POST.name()), eq(SOURCE_ID), eq(VIEWER_ID)))
                    .thenReturn(new GateCheckResponse(false, false, List.of()));

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("viewerUserId == null（未認証）→ fail-closed → 閲覧不可")
        void paywall_anonymousViewer_failClosed() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.CUSTOM,
                    AnnouncementSourceType.BLOG_POST.name(), SOURCE_ID);
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            // 未認証 → evaluateCustom が false を返す（PaymentGateService は呼ばれない）

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, null)).isFalse();
        }

        @Test
        @DisplayName("sourceType == null → fail-closed → 閲覧不可")
        void paywall_nullSourceType_failClosed() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.CUSTOM, null, SOURCE_ID);
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("sourceId == null → fail-closed → 閲覧不可")
        void paywall_nullSourceId_failClosed() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.CUSTOM,
                    AnnouncementSourceType.BLOG_POST.name(), null);
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, VIEWER_ID)).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin 高速パス: CUSTOM でも SystemAdmin は visibility ガードをスキップして閲覧可")
        void paywall_systemAdmin_alwaysAllowed() {
            AnnouncementFeedVisibilityProjection p = projection(
                    FEED_ID, AnnouncementFeedVisibility.CUSTOM,
                    AnnouncementSourceType.BLOG_POST.name(), SOURCE_ID);
            when(membershipBatchQueryService.snapshotForUser(any(), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());
            // SystemAdmin は evaluateCustom に到達しないため PaymentGateService は呼ばれない

            AnnouncementFeedVisibilityResolver directResolver = resolverWithProjection(p);

            assertThat(directResolver.canView(FEED_ID, ADMIN_ID)).isTrue();
        }
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    /**
     * {@link #loadProjections} を指定 Projection で差し込んだテスト用 Resolver を返す。
     *
     * <p>Entity→Projection 変換ロジックをスキップし、Resolver 固有の判定ロジックに集中する。</p>
     */
    private AnnouncementFeedVisibilityResolver resolverWithProjection(
            AnnouncementFeedVisibilityProjection p) {
        return new AnnouncementFeedVisibilityResolver(
                announcementFeedRepository,
                paymentGateService,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                followBatchService,
                auditLogService) {
            @Override
            protected java.util.List<AnnouncementFeedVisibilityProjection> loadProjections(
                    java.util.Collection<Long> ids) {
                return List.of(p);
            }
        };
    }
}
