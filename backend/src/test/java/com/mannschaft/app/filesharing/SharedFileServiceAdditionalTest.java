package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.UpdateFileRequest;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link SharedFileService} の追加単体テスト。未テストメソッドをカバーする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileService 追加単体テスト")
class SharedFileServiceAdditionalTest {

    @Mock
    private SharedFileRepository fileRepository;

    @Mock
    private SharedFileVersionRepository versionRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @InjectMocks
    private SharedFileService service;

    private static final Long FILE_ID = 100L;
    private static final Long FOLDER_ID = 200L;
    private static final Long USER_ID = 10L;

    private SharedFileEntity createFile() {
        return SharedFileEntity.builder()
                .folderId(FOLDER_ID)
                .name("test.pdf")
                .fileKey("uploads/test.pdf")
                .fileSize(1024L)
                .contentType("application/pdf")
                .createdBy(USER_ID)
                .build();
    }

    private FileResponse mockFileResponse() {
        return new FileResponse(FILE_ID, FOLDER_ID, "test.pdf", "uploads/test.pdf",
                1024L, "application/pdf", null, USER_ID, 1, null, null);
    }

    // ========================================
    // listFiles
    // ========================================

    @Nested
    @DisplayName("listFiles")
    class ListFiles {

        @Test
        @DisplayName("正常系: フォルダ内のファイル一覧が返却される")
        void ファイル一覧_正常() {
            SharedFileEntity entity = createFile();
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toFileResponseList(any()))
                    .willReturn(List.of(mockFileResponse()));

            List<FileResponse> result = service.listFiles(FOLDER_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // listFilesPaged
    // ========================================

    @Nested
    @DisplayName("listFilesPaged")
    class ListFilesPaged {

        @Test
        @DisplayName("正常系: ページングでファイル一覧が返却される")
        void ファイル一覧ページング_正常() {
            SharedFileEntity entity = createFile();
            Page<SharedFileEntity> page = new PageImpl<>(List.of(entity));
            given(fileRepository.findByFolderIdOrderByNameAsc(eq(FOLDER_ID), any()))
                    .willReturn(page);
            given(fileSharingMapper.toFileResponse(entity)).willReturn(mockFileResponse());

            Page<FileResponse> result = service.listFilesPaged(FOLDER_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // getFile
    // ========================================

    @Nested
    @DisplayName("getFile")
    class GetFile {

        @Test
        @DisplayName("正常系: ファイル詳細が返却される")
        void ファイル詳細_正常() {
            SharedFileEntity entity = createFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(entity));
            given(fileSharingMapper.toFileResponse(entity)).willReturn(mockFileResponse());

            FileResponse result = service.getFile(FILE_ID);

            assertThat(result.getName()).isEqualTo("test.pdf");
        }

        @Test
        @DisplayName("異常系: ファイル不在でFILE_NOT_FOUND例外")
        void ファイル詳細_不在_例外() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFile(FILE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND));
        }
    }

    // ========================================
    // updateFile
    // ========================================

    @Nested
    @DisplayName("updateFile")
    class UpdateFile {

        @Test
        @DisplayName("正常系: ファイル名・説明・フォルダが更新される")
        void ファイル更新_全フィールド_正常() {
            SharedFileEntity entity = createFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(entity));
            given(fileRepository.save(entity)).willReturn(entity);
            given(fileSharingMapper.toFileResponse(entity)).willReturn(mockFileResponse());
            UpdateFileRequest request = new UpdateFileRequest("renamed.pdf", "新説明", 300L);

            FileResponse result = service.updateFile(FILE_ID, request);

            assertThat(result).isNotNull();
            assertThat(entity.getName()).isEqualTo("renamed.pdf");
            assertThat(entity.getDescription()).isEqualTo("新説明");
            assertThat(entity.getFolderId()).isEqualTo(300L);
        }

        @Test
        @DisplayName("正常系: フィールドがnullの場合は更新されない")
        void ファイル更新_全null_変化なし() {
            SharedFileEntity entity = createFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(entity));
            given(fileRepository.save(entity)).willReturn(entity);
            given(fileSharingMapper.toFileResponse(entity)).willReturn(mockFileResponse());
            UpdateFileRequest request = new UpdateFileRequest(null, null, null);

            service.updateFile(FILE_ID, request);

            assertThat(entity.getName()).isEqualTo("test.pdf");
            assertThat(entity.getFolderId()).isEqualTo(FOLDER_ID);
        }
    }
}
