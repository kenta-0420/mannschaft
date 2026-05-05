package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.service.SharedFileQuotaService;
import com.mannschaft.app.filesharing.service.SharedFileService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFileService} の単体テスト。
 * ファイルのCRUDと F13 Phase 4-ε クォータ統合を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileService 単体テスト")
class SharedFileServiceTest {

    @Mock
    private SharedFileRepository fileRepository;

    @Mock
    private SharedFileVersionRepository versionRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @Mock
    private SharedFolderService folderService;

    @Mock
    private SharedFileQuotaService quotaService;

    /** F13 Phase 5-a: presignUpload メソッド追加に伴い @Mock 追加（他テストへの影響なし）。 */
    @Mock
    private R2StorageService r2StorageService;

    @InjectMocks
    private SharedFileService sharedFileService;

    private static final Long FILE_ID = 100L;
    private static final Long FOLDER_ID = 1L;
    private static final Long USER_ID = 10L;

    private SharedFolderEntity buildFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM)
                .teamId(5L)
                .name("テストフォルダ")
                .build();
    }

    // ========================================
    // createFile
    // ========================================

    @Nested
    @DisplayName("createFile")
    class CreateFile {

        @Test
        @DisplayName("ファイル作成_正常_バージョン1も作成_クォータ加算")
        void ファイル作成_正常_バージョン1も作成_クォータ加算() {
            // Given
            CreateFileRequest request = new CreateFileRequest(
                    FOLDER_ID, "test.pdf", "files/test.pdf", 1024L, "application/pdf", null);

            SharedFolderEntity folder = buildFolder();
            SharedFileEntity savedFile = SharedFileEntity.builder()
                    .folderId(FOLDER_ID).name("test.pdf").fileKey("files/test.pdf")
                    .fileSize(1024L).contentType("application/pdf").createdBy(USER_ID).build();
            FileResponse response = new FileResponse(FILE_ID, FOLDER_ID, "test.pdf", "files/test.pdf",
                    1024L, "application/pdf", null, USER_ID, 1, null, null);

            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder);
            willDoNothing().given(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(1024L));
            given(fileRepository.save(any(SharedFileEntity.class))).willReturn(savedFile);
            given(versionRepository.save(any(SharedFileVersionEntity.class))).willReturn(null);
            given(fileSharingMapper.toFileResponse(savedFile)).willReturn(response);

            // When
            FileResponse result = sharedFileService.createFile(USER_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("test.pdf");
            verify(versionRepository).save(any(SharedFileVersionEntity.class));
            verify(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(1024L));
            verify(quotaService).recordFileUpload(any(SharedFolderEntity.class), nullable(Long.class), eq(1024L), eq(USER_ID));
        }

        @Test
        @DisplayName("ファイル作成_クォータ超過_BusinessException_DB登録されない")
        void ファイル作成_クォータ超過_BusinessException_DB登録されない() {
            // Given
            CreateFileRequest request = new CreateFileRequest(
                    FOLDER_ID, "big.pdf", "files/big.pdf", 999999L, "application/pdf", null);
            SharedFolderEntity folder = buildFolder();

            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder);
            willThrow(new BusinessException(FileSharingErrorCode.STORAGE_QUOTA_EXCEEDED))
                    .given(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(999999L));

            // When & Then
            assertThatThrownBy(() -> sharedFileService.createFile(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.STORAGE_QUOTA_EXCEEDED));
            verify(fileRepository, never()).save(any());
            verify(quotaService, never()).recordFileUpload(any(), anyLong(), anyLong(), anyLong());
        }
    }

    // ========================================
    // deleteFile
    // ========================================

    @Nested
    @DisplayName("deleteFile")
    class DeleteFile {

        @Test
        @DisplayName("ファイル削除_正常_論理削除実行_クォータ減算")
        void ファイル削除_正常_論理削除実行_クォータ減算() {
            // Given
            SharedFileEntity entity = SharedFileEntity.builder()
                    .folderId(FOLDER_ID).name("test.pdf").fileKey("key")
                    .fileSize(1024L).contentType("application/pdf").build();
            SharedFolderEntity folder = buildFolder();

            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(entity));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder);
            given(fileRepository.save(entity)).willReturn(entity);

            // When
            sharedFileService.deleteFile(FILE_ID, USER_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(quotaService).recordFileDeletion(any(SharedFolderEntity.class), eq(FILE_ID), eq(1024L), eq(USER_ID));
        }

        @Test
        @DisplayName("ファイル削除_存在しない_BusinessException")
        void ファイル削除_存在しない_BusinessException() {
            // Given
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sharedFileService.deleteFile(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND));
            verify(quotaService, never()).recordFileDeletion(any(), anyLong(), anyLong(), anyLong());
        }
    }
}
