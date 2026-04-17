package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FriendNotificationDeliveryResponse;
import com.mannschaft.app.social.dto.FriendNotificationSendRequest;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderMemberEntity;
import com.mannschaft.app.social.repository.TeamFriendFolderMemberRepository;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link FriendNotificationService} の単体テスト（F01.5 Phase 2）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FriendNotificationService 単体テスト")
class FriendNotificationServiceTest {

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private TeamFriendRepository teamFriendRepository;

    @Mock
    private TeamFriendFolderRepository teamFriendFolderRepository;

    @Mock
    private TeamFriendFolderMemberRepository folderMemberRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private FriendNotificationService friendNotificationService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;

    // ─────────────────────────────────────────────
    // listFriendNotifications
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("listFriendNotifications")
    class ListFriendNotifications {

        @Test
        @DisplayName("正常系: 権限あり・isRead=null → 全件ページが返る")
        void 権限あり_既読フィルタなし_ページが返る() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            NotificationEntity entity = buildNotificationEntity(1L);
            NotificationResponse response = buildNotificationResponse(1L);
            Page<NotificationEntity> entityPage = new PageImpl<>(List.of(entity));

            doNothing().when(accessControlService)
                    .checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
            given(notificationRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    NotificationScopeType.FRIEND_TEAM.name(), TEAM_ID, pageable))
                    .willReturn(entityPage);
            given(notificationMapper.toNotificationResponse(entity)).willReturn(response);

            // when
            Page<NotificationResponse> result =
                    friendNotificationService.listFriendNotifications(TEAM_ID, USER_ID, null, pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
            verify(notificationRepository).findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    NotificationScopeType.FRIEND_TEAM.name(), TEAM_ID, pageable);
        }

        @Test
        @DisplayName("正常系: 権限あり・isRead=false → 未読フィルタ付きクエリが呼ばれる")
        void 権限あり_isRead_false_未読のみ返る() {
            // given
            Pageable pageable = PageRequest.of(0, 20);
            Page<NotificationEntity> emptyPage = Page.empty();

            doNothing().when(accessControlService)
                    .checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
            given(notificationRepository.findByScopeTypeAndScopeIdAndIsReadOrderByCreatedAtDesc(
                    NotificationScopeType.FRIEND_TEAM.name(), TEAM_ID, false, pageable))
                    .willReturn(emptyPage);

            // when
            Page<NotificationResponse> result =
                    friendNotificationService.listFriendNotifications(TEAM_ID, USER_ID, false, pageable);

            // then
            assertThat(result.isEmpty()).isTrue();
            verify(notificationRepository).findByScopeTypeAndScopeIdAndIsReadOrderByCreatedAtDesc(
                    NotificationScopeType.FRIEND_TEAM.name(), TEAM_ID, false, pageable);
            verify(notificationRepository, never()).findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────
    // sendFriendNotification
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("sendFriendNotification")
    class SendFriendNotification {

        @Test
        @DisplayName("正常系(TEAMS): フレンド関係あり → DeliveryResponse が返る")
        void TEAMS指定_正常_DeliveryResponseが返る() {
            // given
            FriendNotificationSendRequest request = buildTeamsRequest(List.of(TARGET_TEAM_ID));

            doNothing().when(accessControlService)
                    .checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID))
                    .willReturn(Optional.of(buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L)));
            given(userRoleRepository.findAdminUserIdsByTeamIds(List.of(TARGET_TEAM_ID)))
                    .willReturn(List.of(100L, 101L));
            given(notificationService.createNotification(anyLong(), anyString(), any(), anyString(),
                    any(), anyString(), anyLong(), any(), anyLong(), any(), anyLong()))
                    .willReturn(null);

            // when
            FriendNotificationDeliveryResponse result =
                    friendNotificationService.sendFriendNotification(TEAM_ID, USER_ID, request);

            // then
            assertThat(result.getQueuedTeamsCount()).isEqualTo(1);
            assertThat(result.getQueuedAdminsCount()).isEqualTo(2);
            assertThat(result.getDeliveryId()).startsWith("frdl_");
            assertThat(result.getQueuedAt()).isNotNull();
            verify(notificationService, times(2)).createNotification(
                    anyLong(), anyString(), any(), anyString(),
                    any(), anyString(), anyLong(), any(), anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("正常系(FOLDER): フォルダメンバーからチームを解決 → DeliveryResponse が返る")
        void FOLDER指定_正常_DeliveryResponseが返る() {
            // given
            FriendNotificationSendRequest request = buildFolderRequest(50L);
            TeamFriendFolderEntity folder = buildFolder(50L, TEAM_ID);
            TeamFriendFolderMemberEntity member = buildFolderMember(50L, 1L);
            TeamFriendEntity friend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);

            doNothing().when(accessControlService)
                    .checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
            given(teamFriendFolderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(50L, TEAM_ID))
                    .willReturn(Optional.of(folder));
            given(folderMemberRepository.findByFolderId(50L))
                    .willReturn(List.of(member));
            given(teamFriendRepository.findById(1L))
                    .willReturn(Optional.of(friend));
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID))
                    .willReturn(Optional.of(friend));
            given(userRoleRepository.findAdminUserIdsByTeamIds(List.of(TARGET_TEAM_ID)))
                    .willReturn(List.of(100L));
            given(notificationService.createNotification(anyLong(), anyString(), any(), anyString(),
                    any(), anyString(), anyLong(), any(), anyLong(), any(), anyLong()))
                    .willReturn(null);

            // when
            FriendNotificationDeliveryResponse result =
                    friendNotificationService.sendFriendNotification(TEAM_ID, USER_ID, request);

            // then
            assertThat(result.getQueuedTeamsCount()).isEqualTo(1);
            assertThat(result.getQueuedAdminsCount()).isEqualTo(1);
            assertThat(result.getDeliveryId()).startsWith("frdl_");
        }

        @Test
        @DisplayName("異常系: フレンド関係にないチームを指定 → BusinessException(FRIEND_NOTIFICATION_TARGET_NOT_FRIEND)")
        void フレンドでないチーム_BusinessException() {
            // given
            FriendNotificationSendRequest request = buildTeamsRequest(List.of(TARGET_TEAM_ID));

            doNothing().when(accessControlService)
                    .checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID))
                    .willReturn(Optional.empty());
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TARGET_TEAM_ID, TEAM_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() ->
                    friendNotificationService.sendFriendNotification(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_NOTIFICATION_TARGET_NOT_FRIEND));
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private NotificationEntity buildNotificationEntity(Long id) {
        NotificationEntity entity = NotificationEntity.builder()
                .userId(USER_ID)
                .notificationType("FRIEND_ANNOUNCEMENT")
                .title("テスト通知")
                .body("本文")
                .sourceType("FRIEND_TEAM")
                .sourceId(TEAM_ID)
                .scopeType(NotificationScopeType.FRIEND_TEAM)
                .scopeId(TARGET_TEAM_ID)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private NotificationResponse buildNotificationResponse(Long id) {
        return new NotificationResponse(
                id, USER_ID, "FRIEND_ANNOUNCEMENT", "NORMAL",
                "テスト通知", "本文", "FRIEND_TEAM", TEAM_ID,
                "FRIEND_TEAM", TARGET_TEAM_ID, null, USER_ID,
                false, null, null, null, LocalDateTime.now()
        );
    }

    private FriendNotificationSendRequest buildTeamsRequest(List<Long> teamIds) {
        FriendNotificationSendRequest request = new FriendNotificationSendRequest();
        ReflectionTestUtils.setField(request, "targetType", "TEAMS");
        ReflectionTestUtils.setField(request, "targetTeamIds", teamIds);
        ReflectionTestUtils.setField(request, "title", "テスト通知タイトル");
        ReflectionTestUtils.setField(request, "body", "テスト本文");
        return request;
    }

    private FriendNotificationSendRequest buildFolderRequest(Long folderId) {
        FriendNotificationSendRequest request = new FriendNotificationSendRequest();
        ReflectionTestUtils.setField(request, "targetType", "FOLDER");
        ReflectionTestUtils.setField(request, "targetFolderId", folderId);
        ReflectionTestUtils.setField(request, "title", "テスト通知タイトル");
        ReflectionTestUtils.setField(request, "body", "テスト本文");
        return request;
    }

    private TeamFriendEntity buildTeamFriend(Long teamAId, Long teamBId, Long id) {
        long aId = Math.min(teamAId, teamBId);
        long bId = Math.max(teamAId, teamBId);
        TeamFriendEntity entity = TeamFriendEntity.builder()
                .teamAId(aId)
                .teamBId(bId)
                .aFollowId(100L)
                .bFollowId(200L)
                .establishedAt(LocalDateTime.now())
                .isPublic(false)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private TeamFriendFolderEntity buildFolder(Long folderId, Long ownerTeamId) {
        TeamFriendFolderEntity entity = TeamFriendFolderEntity.builder()
                .ownerTeamId(ownerTeamId)
                .name("テストフォルダ")
                .color("#6B7280")
                .isDefault(false)
                .folderOrder(0)
                .build();
        ReflectionTestUtils.setField(entity, "id", folderId);
        return entity;
    }

    private TeamFriendFolderMemberEntity buildFolderMember(Long folderId, Long teamFriendId) {
        TeamFriendFolderMemberEntity entity = TeamFriendFolderMemberEntity.builder()
                .folderId(folderId)
                .teamFriendId(teamFriendId)
                .addedBy(USER_ID)
                .addedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(entity, "id", 1L);
        return entity;
    }
}
