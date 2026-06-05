package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.announcement.adapter.AnnouncementChannelAdapter;
import com.mannschaft.app.social.announcement.adapter.AnnouncementChannelAdapterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AnnouncementBroadcastService} の単体テスト。
 * 告知ウィザードの認可・バリデーション・チャネルアダプター呼び出しを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementBroadcastService 単体テスト")
class AnnouncementBroadcastServiceTest {

    @Mock
    private AnnouncementFeedService announcementFeedService;

    @Mock
    private AnnouncementChannelAdapterRegistry adapterRegistry;

    @Mock
    private AnnouncementRangeTemplateRepository templateRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private AnnouncementBroadcastService broadcastService;

    // ──────────────────────────────────────────────────────────────────────────
    // テストデータ定数
    // ──────────────────────────────────────────────────────────────────────────

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long CONTENT_ID = 100L;
    private static final Long FEED_ID = 999L;

    // ──────────────────────────────────────────────────────────────────────────
    // MEMBER 優先度制限
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MEMBER が NORMAL 以外の優先度を指定した場合")
    class MemberPriorityRestriction {

        @Test
        @DisplayName("priority = IMPORTANT を指定すると BROADCAST_001 エラーが発生すること")
        void throwsBroadcast001WhenMemberSpecifiesImportantPriority() {
            // given
            BroadcastRequest req = buildBroadcastRequest("TEAM", null, "IMPORTANT", AnnouncementChannel.BULLETIN_THREAD);

            // メンバーシップ検証は通過
            // given(accessControlService.checkMembership(...)) は void なので何もしない（デフォルト）
            // ADMIN でない（MEMBER 相当）
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);

            // when / then
            assertThatThrownBy(() -> broadcastService.broadcast(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode().getCode()).isEqualTo("BROADCAST_001");
                    });

            // チャネルアダプターは呼ばれないこと
            verify(adapterRegistry, never()).getAdapter(any());
        }

        @Test
        @DisplayName("priority = URGENT を指定しても ADMIN なら例外が発生しないこと")
        void doesNotThrowWhenAdminSpecifiesUrgentPriority() {
            // given
            BroadcastRequest req = buildBroadcastRequest("TEAM", null, "URGENT", AnnouncementChannel.BULLETIN_THREAD);

            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(true);

            AnnouncementChannelAdapter mockAdapter = buildMockAdapter(AnnouncementSourceType.BULLETIN_THREAD, CONTENT_ID);
            given(adapterRegistry.getAdapter(AnnouncementChannel.BULLETIN_THREAD)).willReturn(mockAdapter);

            AnnouncementFeedEntity mockFeed = buildMockFeed(FEED_ID);
            given(announcementFeedService.createFromBroadcast(
                    any(), anyLong(), any(), anyLong(), anyLong(),
                    anyString(), any(), any(), any(), any()))
                    .willReturn(mockFeed);

            // when
            BroadcastResult result = broadcastService.broadcast(req);

            // then: 例外なし、結果が返ること
            assertThat(result).isNotNull();
            assertThat(result.getContentId()).isEqualTo(CONTENT_ID);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // target_team_ids バリデーション
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("target_team_ids バリデーション")
    class TargetTeamIdsValidation {

        @Test
        @DisplayName("ORGANIZATION スコープで無効なチームIDが含まれると BROADCAST_002 エラーが発生すること")
        void throwsBroadcast002WhenTargetTeamIdsContainInvalidTeamId() {
            // given
            List<Long> targetTeamIds = List.of(11L, 12L, 999L); // 999L は組織配下に存在しない
            BroadcastRequest req = buildBroadcastRequest("ORGANIZATION", targetTeamIds, "NORMAL", AnnouncementChannel.BULLETIN_THREAD);

            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION")).willReturn(true);
            // 組織配下のチームは 11L と 12L のみ
            given(userRoleRepository.findTeamIdsByOrganizationId(SCOPE_ID))
                    .willReturn(List.of(11L, 12L));

            // when / then
            assertThatThrownBy(() -> broadcastService.broadcast(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode().getCode()).isEqualTo("BROADCAST_002");
                    });

            // チャネルアダプターは呼ばれないこと
            verify(adapterRegistry, never()).getAdapter(any());
        }

        @Test
        @DisplayName("TEAM スコープでは target_team_ids が指定されても検証をスキップすること")
        void skipsTargetTeamValidationForTeamScope() {
            // given
            List<Long> targetTeamIds = List.of(999L); // TEAM スコープでは無視される
            BroadcastRequest req = buildBroadcastRequest("TEAM", targetTeamIds, "NORMAL", AnnouncementChannel.BULLETIN_THREAD);

            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(true);

            AnnouncementChannelAdapter mockAdapter = buildMockAdapter(AnnouncementSourceType.BULLETIN_THREAD, CONTENT_ID);
            given(adapterRegistry.getAdapter(AnnouncementChannel.BULLETIN_THREAD)).willReturn(mockAdapter);

            AnnouncementFeedEntity mockFeed = buildMockFeed(FEED_ID);
            given(announcementFeedService.createFromBroadcast(
                    any(), anyLong(), any(), anyLong(), anyLong(),
                    anyString(), any(), any(), any(), any()))
                    .willReturn(mockFeed);

            // when
            BroadcastResult result = broadcastService.broadcast(req);

            // then: BROADCAST_002 は発生せず、処理が続くこと
            assertThat(result).isNotNull();
            verify(userRoleRepository, never()).findTeamIdsByOrganizationId(anyLong());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 正常系: チャネルアダプター呼び出し
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("正常系 — チャネルアダプター呼び出しとフィード登録")
    class BroadcastSuccess {

        @Test
        @DisplayName("BULLETIN_THREAD チャネルで正常に broadcast() が完了し、AnnouncementFeedService.createFromBroadcast() が呼ばれること")
        void broadcastWithBulletinThreadChannelCallsCreateFromBroadcast() {
            // given
            BroadcastRequest req = buildBroadcastRequest("TEAM", null, "NORMAL", AnnouncementChannel.BULLETIN_THREAD);

            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);

            AnnouncementChannelAdapter mockAdapter = buildMockAdapter(AnnouncementSourceType.BULLETIN_THREAD, CONTENT_ID);
            given(adapterRegistry.getAdapter(AnnouncementChannel.BULLETIN_THREAD)).willReturn(mockAdapter);

            AnnouncementFeedEntity mockFeed = buildMockFeed(FEED_ID);
            given(announcementFeedService.createFromBroadcast(
                    any(), anyLong(), any(), anyLong(), anyLong(),
                    anyString(), any(), any(), any(), any()))
                    .willReturn(mockFeed);

            // when
            BroadcastResult result = broadcastService.broadcast(req);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getAnnouncementFeedId()).isEqualTo(FEED_ID);
            assertThat(result.getContentId()).isEqualTo(CONTENT_ID);
            assertThat(result.getChannel()).isEqualTo(AnnouncementChannel.BULLETIN_THREAD);

            verify(adapterRegistry).getAdapter(AnnouncementChannel.BULLETIN_THREAD);
            verify(announcementFeedService).createFromBroadcast(
                    any(), anyLong(), any(), anyLong(), anyLong(),
                    anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("アダプターが contentId を返し、buildContentUrl が実行されること")
        void broadcastBuildsContentUrl() {
            // given
            BroadcastRequest req = buildBroadcastRequest("TEAM", null, "NORMAL", AnnouncementChannel.BULLETIN_THREAD);

            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);

            AnnouncementChannelAdapter mockAdapter = buildMockAdapter(AnnouncementSourceType.BULLETIN_THREAD, CONTENT_ID);
            given(adapterRegistry.getAdapter(AnnouncementChannel.BULLETIN_THREAD)).willReturn(mockAdapter);

            AnnouncementFeedEntity mockFeed = buildMockFeed(FEED_ID);
            given(announcementFeedService.createFromBroadcast(
                    any(), anyLong(), any(), anyLong(), anyLong(),
                    anyString(), any(), any(), any(), any()))
                    .willReturn(mockFeed);

            // when
            BroadcastResult result = broadcastService.broadcast(req);

            // then: contentUrl が生成されていること
            assertThat(result.getContentUrl()).isNotNull();
            verify(mockAdapter).buildContentUrl("TEAM", SCOPE_ID, CONTENT_ID);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ──────────────────────────────────────────────────────────────────────────

    private BroadcastRequest buildBroadcastRequest(
            String scopeType,
            List<Long> targetTeamIds,
            String priority,
            AnnouncementChannel channel) {

        return BroadcastRequest.builder()
                .channel(channel)
                .targetRole("MEMBERS_AND_ABOVE")
                .targetTeamIds(targetTeamIds)
                .priority(priority)
                .content(AnnouncementContentRequest.builder()
                        .title("テスト告知タイトル")
                        .body("テスト告知本文")
                        .build())
                .callerUserId(USER_ID)
                .scopeType(scopeType)
                .scopeId(SCOPE_ID)
                .build();
    }

    private AnnouncementChannelAdapter buildMockAdapter(
            AnnouncementSourceType sourceType, Long contentId) {

        AnnouncementChannelAdapter mockAdapter = org.mockito.Mockito.mock(AnnouncementChannelAdapter.class);
        given(mockAdapter.getSourceType()).willReturn(sourceType);
        given(mockAdapter.createContent(any(), anyString(), anyLong(), anyString(), anyLong()))
                .willReturn(contentId);
        given(mockAdapter.buildContentUrl(anyString(), anyLong(), anyLong()))
                .willReturn("/teams/" + SCOPE_ID + "/threads/" + contentId);
        return mockAdapter;
    }

    private AnnouncementFeedEntity buildMockFeed(Long feedId) {
        // id は @GeneratedValue なので mock で getId() を差し替える
        AnnouncementFeedEntity mockFeed = org.mockito.Mockito.mock(AnnouncementFeedEntity.class);
        given(mockFeed.getId()).willReturn(feedId);
        return mockFeed;
    }
}
