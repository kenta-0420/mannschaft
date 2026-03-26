package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
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

    @InjectMocks
    private SharedFolderService sharedFolderService;

    private static final Long FOLDER_ID = 100L;
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
                    "新フォルダ", "説明", null, "TEAM");

            SharedFolderEntity savedEntity = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .name("新フォルダ").createdBy(USER_ID).build();
            FolderResponse response = new FolderResponse(FOLDER_ID, "TEAM", TEAM_ID, null, null,
                    null, "新フォルダ", "説明", USER_ID, null, null);

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
                    "重複フォルダ", null, null, "TEAM");

            given(folderRepository.existsByParentIdAndName(null, "重複フォルダ")).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> sharedFolderService.createTeamFolder(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NAME_DUPLICATE));
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
            sharedFolderService.deleteFolder(FOLDER_ID);

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
            assertThatThrownBy(() -> sharedFolderService.deleteFolder(FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }
    }
}
