package com.mannschaft.app.chat.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
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
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F00 Phase B（積み残し根治） — {@link ChatMessageVisibilityResolver} 単体テスト。
 *
 * <p>実機 E2E で「問い合わせ通知が一件も作成されない（visibility deny）」障害を捕捉した。
 * 真因は {@code CHAT_MESSAGE} が {@code NotificationSourceTypeMapper} に登録されているのに
 * 対応 Resolver が実装されておらず、{@code ContentVisibilityChecker.canView(CHAT_MESSAGE, …)} が
 * fail-closed で必ず false を返していたこと。本テストはその Resolver（§12.3.1 の最小実装）が
 * 「公開チャンネル＝スコープ（TEAM/ORGANIZATION）直接所属者は閲覧可・非所属者は不可・
 * 問い合わせチャンネル＝ADMIN/DEPUTY_ADMIN 限定・PRIVATE チャンネル＝fail-closed・
 * 不存在は fail-closed」を満たすことを検証する（粒度は検分是正 2026-07-11）。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは CHAT_MESSAGE 固有の
 * スコープ解決（メッセージ→チャンネル scope）と所属判定を重点的に確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageVisibilityResolver — 単体テスト")
class ChatMessageVisibilityResolverTest {

    private static final long MSG_ID = 5001L;
    private static final long TEAM_ID = 100L;
    private static final long SENDER_ID = 999L;

    @Mock
    private ChatMessageRepository messageRepository;

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    private VisibilityMetrics visibilityMetrics;
    private ChatMessageVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new ChatMessageVisibilityResolver(
                messageRepository,
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                null,            // FollowBatchService 不要
                auditLogService);
    }

    @Test
    @DisplayName("referenceType() は CHAT_MESSAGE を返す")
    void referenceType_is_CHAT_MESSAGE() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.CHAT_MESSAGE);
    }

    @Nested
    @DisplayName("入口ガード")
    class EntryGuard {

        @Test
        @DisplayName("contentId=null は false")
        void canView_nullId_false() {
            assertThat(resolver.canView(null, 1L)).isFalse();
            verifyNoInteractions(messageRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("空 ids は空 Set")
        void filterAccessible_empty_emptySet() {
            assertThat(resolver.filterAccessible(List.of(), 1L)).isEmpty();
            verifyNoInteractions(messageRepository);
            verifyNoInteractions(membershipBatchQueryService);
        }

        @Test
        @DisplayName("Repository が空を返す ID は不存在として false（IDOR 防止・論理削除含む）")
        void canView_unknownId_false() {
            when(messageRepository.findVisibilityProjectionsByIdIn(eq(List.of(99L))))
                    .thenReturn(List.of());

            assertThat(resolver.canView(99L, 1L)).isFalse();
            verifyNoInteractions(membershipBatchQueryService);
        }
    }

    @Nested
    @DisplayName("スコープ所属判定（SCOPE_AFFILIATED）")
    class ScopeAffiliation {

        @Test
        @DisplayName("チームメンバー（MEMBER）はチームチャンネルのメッセージを閲覧可 → 通知許可")
        void team_member_can_view() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(10L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(MSG_ID, 10L)).isTrue();
        }

        @Test
        @DisplayName("チーム ADMIN は閲覧可（問い合わせ通知の実受信者ケース）")
        void team_admin_can_view() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(20L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "ADMIN"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(MSG_ID, 20L)).isTrue();
        }

        @Test
        @DisplayName("チーム非所属ユーザーは閲覧不可 → 通知 deny")
        void non_member_cannot_view() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(30L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(MSG_ID, 30L)).isFalse();
        }

        @Test
        @DisplayName("未認証ユーザー（viewerUserId=null）は閲覧不可（fail-closed）")
        void anonymous_cannot_view() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(null), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(MSG_ID, null)).isFalse();
        }

        @Test
        @DisplayName("組織チャンネル（scopeType=ORGANIZATION）の組織メンバーは閲覧可")
        void org_member_can_view() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(MSG_ID, "ORGANIZATION", 200L, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(40L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("ORGANIZATION", 200L), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(MSG_ID, 40L)).isTrue();
        }

        @Test
        @DisplayName("スコープ無し（DM 等・scopeType=null）は fail-closed で不可視")
        void dm_channel_scope_null_is_denied() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(projection(MSG_ID, null, null, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(50L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(MSG_ID, 50L)).isFalse();
        }
    }

    @Nested
    @DisplayName("粒度是正（検分指摘）: PRIVATE チャンネルと問い合わせチャンネル")
    class GranularityFix {

        @Test
        @DisplayName("PRIVATE チャンネル（非 inquiry）のメッセージはスコープ所属メンバーにも fail-closed で不可視")
        void private_channel_denied_even_to_scope_member() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(privateProjection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(10L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(MSG_ID, 10L))
                    .as("isPrivate=true（非 inquiry）はチャンネルメンバーシップ判定を持たない現段階では fail-closed")
                    .isFalse();
        }

        @Test
        @DisplayName("問い合わせチャンネルのメッセージは一般メンバー（MEMBER）に不可視")
        void inquiry_channel_denied_to_regular_member() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(inquiryProjection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(10L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(MSG_ID, 10L))
                    .as("問い合わせは管理者宛の内容のため一般メンバーには開かない")
                    .isFalse();
        }

        @Test
        @DisplayName("問い合わせチャンネルのメッセージはチーム ADMIN に可視（通知受信者・回帰ガード）")
        void inquiry_channel_allowed_to_admin() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(inquiryProjection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(20L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "ADMIN"),
                            Map.of(), Set.of(), Set.of()));

            assertThat(resolver.canView(MSG_ID, 20L)).isTrue();
        }

        @Test
        @DisplayName("問い合わせチャンネルのメッセージはチーム DEPUTY_ADMIN にも可視（受信者集合と完全一致）")
        void inquiry_channel_allowed_to_deputy_admin() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(inquiryProjection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(21L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "DEPUTY_ADMIN"),
                            Map.of(), Set.of(), Set.of()));

            // 注意: RolePriority.isAtLeast("DEPUTY_ADMIN","ADMIN") は false（3<=2 不成立）のため
            // ADMINS_AND_ABOVE（閾値 "ADMIN"）経路では DEPUTY_ADMIN が deny になる。
            // 受信者集合（ADMIN/DEPUTY_ADMIN）との完全一致には閾値 "DEPUTY_ADMIN" の判定が必要
            // （本テストがその実装選択を固定する）。
            assertThat(resolver.canView(MSG_ID, 21L)).isTrue();
        }

        @Test
        @DisplayName("問い合わせチャンネルは非所属ユーザーに不可視")
        void inquiry_channel_denied_to_non_member() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(inquiryProjection(MSG_ID, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(30L), anySet(), anySet()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(MSG_ID, 30L)).isFalse();
        }
    }

    @Nested
    @DisplayName("バッチ呼び出し")
    class Batch {

        @Test
        @DisplayName("filterAccessible は Repository を 1 回・MembershipBatchQueryService を 1 回呼ぶ")
        void single_repo_call_for_batch() {
            when(messageRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of(
                            projection(1L, "TEAM", TEAM_ID, SENDER_ID),
                            projection(2L, "TEAM", TEAM_ID, SENDER_ID)));
            when(membershipBatchQueryService.snapshotForUser(eq(10L), anySet(), anySet()))
                    .thenReturn(new UserScopeRoleSnapshot(false,
                            Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                            Map.of(), Set.of(), Set.of()));

            Set<Long> result = resolver.filterAccessible(List.of(1L, 2L), 10L);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
            verify(messageRepository, times(1)).findVisibilityProjectionsByIdIn(any());
            verify(membershipBatchQueryService, times(1))
                    .snapshotForUser(eq(10L), anySet(), anySet());
        }
    }

    /** 公開チャンネル（isPrivate=false / isInquiryChannel=false）の Projection。 */
    private static ChatMessageVisibilityProjection projection(
            Long id, String scopeType, Long scopeId, Long authorUserId) {
        return new ChatMessageVisibilityProjection(
                id, scopeType, scopeId, authorUserId, false, false);
    }

    /** PRIVATE チャンネル（isPrivate=true / 非 inquiry）の Projection。 */
    private static ChatMessageVisibilityProjection privateProjection(
            Long id, String scopeType, Long scopeId, Long authorUserId) {
        return new ChatMessageVisibilityProjection(
                id, scopeType, scopeId, authorUserId, true, false);
    }

    /** 問い合わせチャンネル（isInquiryChannel=true）の Projection。 */
    private static ChatMessageVisibilityProjection inquiryProjection(
            Long id, String scopeType, Long scopeId, Long authorUserId) {
        return new ChatMessageVisibilityProjection(
                id, scopeType, scopeId, authorUserId, false, true);
    }
}
