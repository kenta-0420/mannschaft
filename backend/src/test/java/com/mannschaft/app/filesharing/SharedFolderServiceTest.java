package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFolderAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFolderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFolderService} の単体テスト。
 * フォルダのCRUDと階層管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFolderService 単体テスト")
class SharedFolderServiceTest {

    @Mock
    private SharedFolderRepository folderRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;

    /** 認可根治 Wave7: SharedFolderService に per-scope 認可が入ったため注入対象に追加。 */
    @Mock
    private AccessControlService accessControlService;

    /**
     * 親フォルダの接ぎ木封鎖を担う認可ガード。
     * 判定そのものの網羅検証は {@link SharedFolderAccessGuardTest} が担い、
     * 本テストはサービスがこのガードへ確実に委譲し、その拒否を伝播することを検証する。
     */
    @Mock
    private SharedFolderAccessGuard folderAccessGuard;

    @InjectMocks
    private SharedFolderService sharedFolderService;

    private static final Long FOLDER_ID = 100L;
    private static final Long PARENT_FOLDER_ID = 50L;
    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("createTeamFolder")
    class CreateTeamFolder {

        @Test
        @DisplayName("チームフォルダ作成_正常_レスポンス返却")
        void チームフォルダ作成_正常_レスポンス返却() {
            // Given
            CreateFolderRequest request = new CreateFolderRequest(
                    "新フォルダ", "説明", null, "TEAM", null, null, null);

            SharedFolderEntity savedEntity = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .name("新フォルダ").createdBy(USER_ID).build();
            FolderResponse response = new FolderResponse(FOLDER_ID, "TEAM", TEAM_ID, null, null,
                    null, "新フォルダ", "説明", USER_ID, null, null, null, null);

            given(folderRepository.existsByParentIdAndName(null, "新フォルダ")).willReturn(false);
            given(folderRepository.save(any(SharedFolderEntity.class))).willReturn(savedEntity);
            given(fileSharingMapper.toFolderResponse(savedEntity)).willReturn(response);

            // When
            FolderResponse result = sharedFolderService.createTeamFolder(TEAM_ID, USER_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("新フォルダ");
        }

        @Test
        @DisplayName("チームフォルダ作成_名前重複_BusinessException")
        void チームフォルダ作成_名前重複_BusinessException() {
            // Given
            CreateFolderRequest request = new CreateFolderRequest(
                    "重複フォルダ", null, null, "TEAM", null, null, null);

            given(folderRepository.existsByParentIdAndName(null, "重複フォルダ")).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> sharedFolderService.createTeamFolder(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NAME_DUPLICATE));
        }

        @Test
        @DisplayName("親フォルダ指定時は接ぎ木封鎖ガードへ実体・作成先スコープを渡して委譲する")
        void 親フォルダ指定_ガードへ委譲() {
            CreateFolderRequest request = new CreateFolderRequest(
                    "子フォルダ", null, PARENT_FOLDER_ID, "TEAM", null, null, null);
            SharedFolderEntity parent = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .name("親フォルダ").createdBy(USER_ID).build();
            SharedFolderEntity savedEntity = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID).parentId(PARENT_FOLDER_ID)
                    .name("子フォルダ").createdBy(USER_ID).build();
            given(folderRepository.findById(PARENT_FOLDER_ID)).willReturn(Optional.of(parent));
            given(folderRepository.existsByParentIdAndName(PARENT_FOLDER_ID, "子フォルダ")).willReturn(false);
            given(folderRepository.save(any(SharedFolderEntity.class))).willReturn(savedEntity);
            given(fileSharingMapper.toFolderResponse(savedEntity)).willReturn(
                    new FolderResponse(FOLDER_ID, "TEAM", TEAM_ID, null, null,
                            null, "子フォルダ", null, USER_ID, null, null, null, null));

            sharedFolderService.createTeamFolder(TEAM_ID, USER_ID, request);

            // 判定材料は親フォルダ実体・作成先スコープ種別・作成先スコープ ID の 3 点である。
            verify(folderAccessGuard).requireParentWithinScope(parent, FileScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("接ぎ木封鎖ガードの拒否は 404 として伝播し、フォルダは保存されない")
        void 親フォルダ拒否_保存されない() {
            CreateFolderRequest request = new CreateFolderRequest(
                    "子フォルダ", null, PARENT_FOLDER_ID, "TEAM", null, null, null);
            SharedFolderEntity foreignParent = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(999L)
                    .name("他チームのフォルダ").createdBy(USER_ID).build();
            given(folderRepository.findById(PARENT_FOLDER_ID)).willReturn(Optional.of(foreignParent));
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderAccessGuard)
                    .requireParentWithinScope(foreignParent, FileScopeType.TEAM, TEAM_ID);

            assertThatThrownBy(() -> sharedFolderService.createTeamFolder(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
            verify(folderRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteFolder")
    class DeleteFolder {

        @Test
        @DisplayName("フォルダ削除_正常_論理削除実行")
        void フォルダ削除_正常_論理削除実行() {
            // Given
            SharedFolderEntity entity = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .name("フォルダ").createdBy(USER_ID).build();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(entity));

            // When
            sharedFolderService.deleteFolder(FOLDER_ID, USER_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(folderRepository).save(entity);
        }

        @Test
        @DisplayName("フォルダ削除_存在しない_BusinessException")
        void フォルダ削除_存在しない_BusinessException() {
            // Given
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sharedFolderService.deleteFolder(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }
    }
}
