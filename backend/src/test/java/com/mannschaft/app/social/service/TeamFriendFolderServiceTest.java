package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.CreateFolderRequest;
import com.mannschaft.app.social.dto.TeamFriendFolderView;
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
 * {@link TeamFriendFolderService} の単体テスト。
 * フォルダ一覧・作成・削除・メンバー追加・メンバー削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFriendFolderService 単体テスト")
class TeamFriendFolderServiceTest {

    @Mock
    private TeamFriendFolderRepository folderRepository;

    @Mock
    private TeamFriendFolderMemberRepository folderMemberRepository;

    @Mock
    private TeamFriendRepository teamFriendRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TeamFriendFolderService teamFriendFolderService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long FOLDER_ID = 100L;
    private static final Long TEAM_FRIEND_ID = 200L;

    // ========================================
    // listFolders
    // ========================================
    @Nested
    @DisplayName("listFolders")
    class ListFolders {

        @Test
        @DisplayName("正常系: フォルダ一覧を返す")
        void フォルダ一覧を返す() {
            // given
            TeamFriendFolderEntity folder1 = buildFolder(FOLDER_ID, TEAM_ID, "系列校");
            TeamFriendFolderEntity folder2 = buildFolder(101L, TEAM_ID, "練習試合候補");

            given(folderRepository.findByOwnerTeamIdAndDeletedAtIsNullOrderByFolderOrder(TEAM_ID))
                    .willReturn(List.of(folder1, folder2));
            given(folderMemberRepository.findByFolderId(anyLong())).willReturn(List.of());

            // when
            List<TeamFriendFolderView> result = teamFriendFolderService.listFolders(TEAM_ID, USER_ID);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("系列校");
            assertThat(result.get(1).getName()).isEqualTo("練習試合候補");
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }
    }

    // ========================================
    // createFolder
    // ========================================
    @Nested
    @DisplayName("createFolder")
    class CreateFolder {

        @Test
        @DisplayName("正常系: フォルダが作成される")
        void フォルダが作成される() {
            // given
            CreateFolderRequest request = new CreateFolderRequest("系列校", "系列校フォルダ", "#10B981");
            TeamFriendFolderEntity savedFolder = buildFolder(FOLDER_ID, TEAM_ID, "系列校");

            given(folderRepository.countByOwnerTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(3L);
            given(folderRepository.save(any(TeamFriendFolderEntity.class))).willReturn(savedFolder);
            given(folderMemberRepository.findByFolderId(FOLDER_ID)).willReturn(List.of());
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            TeamFriendFolderView result = teamFriendFolderService.createFolder(TEAM_ID, request, USER_ID);

            // then
            assertThat(result.getName()).isEqualTo("系列校");
            verify(folderRepository).save(any(TeamFriendFolderEntity.class));
        }

        @Test
        @DisplayName("異常系: 上限20件超過 → BusinessException(FRIEND_FOLDER_LIMIT_EXCEEDED)")
        void 上限超過_例外() {
            // given
            CreateFolderRequest request = new CreateFolderRequest("新フォルダ", null, null);
            given(folderRepository.countByOwnerTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(20L);

            // when & then
            assertThatThrownBy(() ->
                    teamFriendFolderService.createFolder(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_FOLDER_LIMIT_EXCEEDED));

            verify(folderRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 権限不足 → BusinessException")
        void 権限不足_例外() {
            // given
            doThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .when(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
            CreateFolderRequest request = new CreateFolderRequest("新フォルダ", null, null);

            // when & then
            assertThatThrownBy(() ->
                    teamFriendFolderService.createFolder(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION));
        }
    }

    // ========================================
    // deleteFolder
    // ========================================
    @Nested
    @DisplayName("deleteFolder")
    class DeleteFolder {

        @Test
        @DisplayName("正常系: 論理削除される（deletedAt セット）")
        void 論理削除される() {
            // given
            TeamFriendFolderEntity folder = buildFolder(FOLDER_ID, TEAM_ID, "系列校");
            assertThat(folder.getDeletedAt()).isNull();

            given(folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(FOLDER_ID, TEAM_ID))
                    .willReturn(Optional.of(folder));
            given(folderRepository.save(any(TeamFriendFolderEntity.class))).willReturn(folder);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            teamFriendFolderService.deleteFolder(TEAM_ID, FOLDER_ID, USER_ID);

            // then
            assertThat(folder.getDeletedAt()).isNotNull();
            verify(folderRepository).save(folder);
        }

        @Test
        @DisplayName("異常系: 権限不足 → BusinessException")
        void 権限不足_例外() {
            // given
            doThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .when(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");

            // when & then
            assertThatThrownBy(() ->
                    teamFriendFolderService.deleteFolder(TEAM_ID, FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION));

            verify(folderRepository, never()).save(any());
        }
    }

    // ========================================
    // addMemberToFolder
    // ========================================
    @Nested
    @DisplayName("addMemberToFolder")
    class AddMemberToFolder {

        @Test
        @DisplayName("正常系: フォルダメンバー追加")
        void フォルダメンバー追加() {
            // given
            TeamFriendFolderEntity folder = buildFolder(FOLDER_ID, TEAM_ID, "系列校");
            TeamFriendEntity friend = buildTeamFriend(TEAM_ID, 20L, TEAM_FRIEND_ID);
            TeamFriendFolderMemberEntity savedMember = TeamFriendFolderMemberEntity.builder()
                    .folderId(FOLDER_ID)
                    .teamFriendId(TEAM_FRIEND_ID)
                    .addedBy(USER_ID)
                    .build();

            given(folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(FOLDER_ID, TEAM_ID))
                    .willReturn(Optional.of(folder));
            given(teamFriendRepository.findById(TEAM_FRIEND_ID)).willReturn(Optional.of(friend));
            given(folderMemberRepository.save(any(TeamFriendFolderMemberEntity.class)))
                    .willReturn(savedMember);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            teamFriendFolderService.addMemberToFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            // then
            verify(folderMemberRepository).save(any(TeamFriendFolderMemberEntity.class));
        }

        @Test
        @DisplayName("異常系: フレンドでない（他チームの関係） → BusinessException(FRIEND_RELATION_NOT_FOUND)")
        void フレンドでない_例外() {
            // given
            TeamFriendFolderEntity folder = buildFolder(FOLDER_ID, TEAM_ID, "系列校");
            // 自チームが含まれないフレンド関係（30L ↔ 40L）
            TeamFriendEntity unrelatedFriend = buildTeamFriend(30L, 40L, TEAM_FRIEND_ID);

            given(folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(FOLDER_ID, TEAM_ID))
                    .willReturn(Optional.of(folder));
            given(teamFriendRepository.findById(TEAM_FRIEND_ID)).willReturn(Optional.of(unrelatedFriend));

            // when & then
            assertThatThrownBy(() ->
                    teamFriendFolderService.addMemberToFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_RELATION_NOT_FOUND));

            verify(folderMemberRepository, never()).save(any());
        }
    }

    // ========================================
    // removeMemberFromFolder
    // ========================================
    @Nested
    @DisplayName("removeMemberFromFolder")
    class RemoveMemberFromFolder {

        @Test
        @DisplayName("正常系: フォルダメンバー削除")
        void フォルダメンバー削除() {
            // given
            TeamFriendFolderEntity folder = buildFolder(FOLDER_ID, TEAM_ID, "系列校");

            given(folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(FOLDER_ID, TEAM_ID))
                    .willReturn(Optional.of(folder));
            given(folderMemberRepository.existsByFolderIdAndTeamFriendId(FOLDER_ID, TEAM_FRIEND_ID))
                    .willReturn(true);
            doNothing().when(folderMemberRepository)
                    .deleteByFolderIdAndTeamFriendId(FOLDER_ID, TEAM_FRIEND_ID);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            teamFriendFolderService.removeMemberFromFolder(TEAM_ID, FOLDER_ID, TEAM_FRIEND_ID, USER_ID);

            // then
            verify(folderMemberRepository).deleteByFolderIdAndTeamFriendId(FOLDER_ID, TEAM_FRIEND_ID);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private TeamFriendFolderEntity buildFolder(Long id, Long ownerTeamId, String name) {
        TeamFriendFolderEntity entity = TeamFriendFolderEntity.builder()
                .ownerTeamId(ownerTeamId)
                .name(name)
                .description("テスト説明")
                .color("#6B7280")
                .isDefault(false)
                .folderOrder(0)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.now());
        ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.now());
        return entity;
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
}
