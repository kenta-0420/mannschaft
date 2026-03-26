package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.service.SharedFileService;
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
 * {@link SharedFileService} の単体テスト。
 * ファイルのCRUDを検証する。
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

    @InjectMocks
    private SharedFileService sharedFileService;

    private static final Long FILE_ID = 100L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("createFile")
    class CreateFile {

        @Test
        @DisplayName("ファイル作成_正常_バージョン1も作成")
        void ファイル作成_正常_バージョン1も作成() {
            // Given
            CreateFileRequest request = new CreateFileRequest(
                    1L, "test.pdf", "files/test.pdf", 1024L, "application/pdf", null);

            SharedFileEntity savedFile = SharedFileEntity.builder()
                    .folderId(1L).name("test.pdf").fileKey("files/test.pdf")
                    .fileSize(1024L).contentType("application/pdf").createdBy(USER_ID).build();
            FileResponse response = new FileResponse(FILE_ID, 1L, "test.pdf", "files/test.pdf",
                    1024L, "application/pdf", null, USER_ID, 1, null, null);

            given(fileRepository.save(any(SharedFileEntity.class))).willReturn(savedFile);
            given(versionRepository.save(any(SharedFileVersionEntity.class))).willReturn(null);
            given(fileSharingMapper.toFileResponse(savedFile)).willReturn(response);

            // When
            FileResponse result = sharedFileService.createFile(USER_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("test.pdf");
            verify(versionRepository).save(any(SharedFileVersionEntity.class));
        }
    }

    @Nested
    @DisplayName("deleteFile")
    class DeleteFile {

        @Test
        @DisplayName("ファイル削除_正常_論理削除実行")
        void ファイル削除_正常_論理削除実行() {
            // Given
            SharedFileEntity entity = SharedFileEntity.builder()
                    .folderId(1L).name("test.pdf").fileKey("key")
                    .fileSize(1024L).contentType("application/pdf").build();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(entity));

            // When
            sharedFileService.deleteFile(FILE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("ファイル削除_存在しない_BusinessException")
        void ファイル削除_存在しない_BusinessException() {
            // Given
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sharedFileService.deleteFile(FILE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND));
        }
    }
}
