package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateVersionRequest;
import com.mannschaft.app.filesharing.dto.FileVersionResponse;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.service.SharedFileQuotaService;
import com.mannschaft.app.filesharing.service.SharedFileService;
import com.mannschaft.app.filesharing.service.SharedFileVersionService;
import com.mannschaft.app.filesharing.service.SharedFolderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
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
 * {@link SharedFileVersionService} の単体テスト。
 * ファイルバージョンの一覧取得・特定バージョン取得・新規バージョン作成と
 * F13 Phase 4-ε クォータ統合を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileVersionService 単体テスト")
class SharedFileVersionServiceTest {

    @Mock
    private SharedFileVersionRepository versionRepository;

    @Mock
    private SharedFileService fileService;

    @Mock
    private SharedFolderService folderService;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @Mock
    private SharedFileQuotaService quotaService;

    @InjectMocks
    private SharedFileVersionService sharedFileVersionService;

    private static final Long FILE_ID = 100L;
    private static final Long FOLDER_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long VERSION_ID = 1L;
    private static final Integer VERSION_NUMBER = 1;
    private static final String FILE_KEY = "files/test-file.pdf";
    private static final Long FILE_SIZE = 1024L;
    private static final String CONTENT_TYPE = "application/pdf";

    private SharedFileVersionEntity createVersionEntity(Integer versionNumber) {
        return SharedFileVersionEntity.builder()
                .fileId(FILE_ID)
                .versionNumber(versionNumber)
                .fileKey(FILE_KEY)
                .fileSize(FILE_SIZE)
                .contentType(CONTENT_TYPE)
                .uploadedBy(USER_ID)
                .comment("テストバージョン")
                .build();
    }

    private FileVersionResponse createVersionResponse(Integer versionNumber) {
        return new FileVersionResponse(VERSION_ID, FILE_ID, versionNumber, FILE_KEY,
                FILE_SIZE, CONTENT_TYPE, USER_ID, "テストバージョン", LocalDateTime.now());
    }

    private SharedFileEntity createFileEntity(Integer currentVersion) {
        return SharedFileEntity.builder()
                .folderId(FOLDER_ID)
                .name("test-file.pdf")
                .fileKey(FILE_KEY)
                .fileSize(FILE_SIZE)
                .contentType(CONTENT_TYPE)
                .createdBy(USER_ID)
                .currentVersion(currentVersion)
                .build();
    }

    private SharedFolderEntity buildFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM)
                .teamId(5L)
                .name("テストフォルダ")
                .build();
    }

    // ========================================
    // listVersions
    // ========================================

    @Nested
    @DisplayName("listVersions")
    class ListVersions {

        @Test
        @DisplayName("正常系: バージョン一覧が返る")
        void バージョン一覧取得_正常_リスト返却() {
            // Given
            SharedFileVersionEntity entity = createVersionEntity(VERSION_NUMBER);
            FileVersionResponse response = createVersionResponse(VERSION_NUMBER);
            given(versionRepository.findByFileIdOrderByVersionNumberDesc(FILE_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toVersionResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<FileVersionResponse> result = sharedFileVersionService.listVersions(FILE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getVersionNumber()).isEqualTo(VERSION_NUMBER);
        }

        @Test
        @DisplayName("正常系: バージョンが存在しない場合は空リスト")
        void バージョン一覧取得_バージョンなし_空リスト() {
            // Given
            given(versionRepository.findByFileIdOrderByVersionNumberDesc(FILE_ID))
                    .willReturn(List.of());
            given(fileSharingMapper.toVersionResponseList(List.of()))
                    .willReturn(List.of());

            // When
            List<FileVersionResponse> result = sharedFileVersionService.listVersions(FILE_ID);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 複数バージョンが降順で返る")
        void バージョン一覧取得_複数バージョン_降順リスト返却() {
            // Given
            SharedFileVersionEntity v2 = createVersionEntity(2);
            SharedFileVersionEntity v1 = createVersionEntity(1);
            FileVersionResponse resp2 = createVersionResponse(2);
            FileVersionResponse resp1 = createVersionResponse(1);

            given(versionRepository.findByFileIdOrderByVersionNumberDesc(FILE_ID))
                    .willReturn(List.of(v2, v1));
            given(fileSharingMapper.toVersionResponseList(List.of(v2, v1)))
                    .willReturn(List.of(resp2, resp1));

            // When
            List<FileVersionResponse> result = sharedFileVersionService.listVersions(FILE_ID);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getVersionNumber()).isEqualTo(2);
            assertThat(result.get(1).getVersionNumber()).isEqualTo(1);
        }
    }

    // ========================================
    // getVersion
    // ========================================

    @Nested
    @DisplayName("getVersion")
    class GetVersion {

        @Test
        @DisplayName("正常系: 特定バージョンが返る")
        void バージョン取得_正常_レスポンス返却() {
            // Given
            SharedFileVersionEntity entity = createVersionEntity(VERSION_NUMBER);
            FileVersionResponse response = createVersionResponse(VERSION_NUMBER);
            given(versionRepository.findByFileIdAndVersionNumber(FILE_ID, VERSION_NUMBER))
                    .willReturn(Optional.of(entity));
            given(fileSharingMapper.toVersionResponse(entity)).willReturn(response);

            // When
            FileVersionResponse result = sharedFileVersionService.getVersion(FILE_ID, VERSION_NUMBER);

            // Then
            assertThat(result.getFileId()).isEqualTo(FILE_ID);
            assertThat(result.getVersionNumber()).isEqualTo(VERSION_NUMBER);
        }

        @Test
        @DisplayName("異常系: バージョンが存在しないでFILE_SHARING_003例外")
        void バージョン取得_バージョン不在_FILE_SHARING_003例外() {
            // Given
            given(versionRepository.findByFileIdAndVersionNumber(FILE_ID, 999))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> sharedFileVersionService.getVersion(FILE_ID, 999))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FILE_SHARING_003"));
        }
    }

    // ========================================
    // createVersion — F13 Phase 4-ε クォータ統合
    // ========================================

    @Nested
    @DisplayName("createVersion")
    class CreateVersion {

        @Test
        @DisplayName("正常系: 新バージョンが作成される_クォータチェック・加算が呼ばれる")
        void バージョン作成_正常_レスポンス返却_クォータ統合() {
            // Given
            CreateVersionRequest request = new CreateVersionRequest(
                    "files/new-version.pdf", 2048L, "application/pdf", "バージョン2");
            SharedFileEntity fileEntity = createFileEntity(1);
            SharedFolderEntity folder = buildFolder();
            SharedFileVersionEntity savedVersion = createVersionEntity(2);
            FileVersionResponse response = createVersionResponse(2);

            given(fileService.findFileOrThrow(FILE_ID)).willReturn(fileEntity);
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder);
            willDoNothing().given(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(2048L));
            given(versionRepository.save(any(SharedFileVersionEntity.class))).willReturn(savedVersion);
            given(fileSharingMapper.toVersionResponse(savedVersion)).willReturn(response);

            // When
            FileVersionResponse result = sharedFileVersionService.createVersion(FILE_ID, USER_ID, request);

            // Then
            assertThat(result.getVersionNumber()).isEqualTo(2);
            verify(versionRepository).save(any(SharedFileVersionEntity.class));
            // F13 Phase 4-ε: クォータチェックと使用量加算の検証
            verify(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(2048L));
            verify(quotaService).recordVersionUpload(any(SharedFolderEntity.class), nullable(Long.class), eq(2048L), eq(USER_ID));
            // ファイルエンティティのバージョンが更新されることを確認
            assertThat(fileEntity.getCurrentVersion()).isEqualTo(2);
            assertThat(fileEntity.getFileKey()).isEqualTo("files/new-version.pdf");
            assertThat(fileEntity.getFileSize()).isEqualTo(2048L);
            assertThat(fileEntity.getContentType()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("異常系: クォータ超過でバージョン作成が拒否される")
        void バージョン作成_クォータ超過_BusinessException_DB登録されない() {
            // Given
            CreateVersionRequest request = new CreateVersionRequest(
                    "files/big-version.pdf", 999999L, "application/pdf", "大きいバージョン");
            SharedFileEntity fileEntity = createFileEntity(1);
            SharedFolderEntity folder = buildFolder();

            given(fileService.findFileOrThrow(FILE_ID)).willReturn(fileEntity);
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder);
            willThrow(new BusinessException(FileSharingErrorCode.STORAGE_QUOTA_EXCEEDED))
                    .given(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(999999L));

            // When & Then
            assertThatThrownBy(() -> sharedFileVersionService.createVersion(FILE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.STORAGE_QUOTA_EXCEEDED));
            verify(versionRepository, never()).save(any());
            verify(quotaService, never()).recordVersionUpload(any(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("正常系: コメントなしでもバージョン作成可能")
        void バージョン作成_コメントなし_レスポンス返却() {
            // Given
            CreateVersionRequest request = new CreateVersionRequest(
                    "files/no-comment.pdf", 512L, "application/pdf", null);
            SharedFileEntity fileEntity = createFileEntity(1);
            SharedFolderEntity folder = buildFolder();
            SharedFileVersionEntity savedVersion = SharedFileVersionEntity.builder()
                    .fileId(FILE_ID)
                    .versionNumber(2)
                    .fileKey("files/no-comment.pdf")
                    .fileSize(512L)
                    .contentType("application/pdf")
                    .uploadedBy(USER_ID)
                    .comment(null)
                    .build();
            FileVersionResponse response = new FileVersionResponse(
                    VERSION_ID, FILE_ID, 2, "files/no-comment.pdf",
                    512L, "application/pdf", USER_ID, null, LocalDateTime.now());

            given(fileService.findFileOrThrow(FILE_ID)).willReturn(fileEntity);
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder);
            willDoNothing().given(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(512L));
            given(versionRepository.save(any(SharedFileVersionEntity.class))).willReturn(savedVersion);
            given(fileSharingMapper.toVersionResponse(savedVersion)).willReturn(response);

            // When
            FileVersionResponse result = sharedFileVersionService.createVersion(FILE_ID, USER_ID, request);

            // Then
            assertThat(result.getComment()).isNull();
            assertThat(result.getVersionNumber()).isEqualTo(2);
            verify(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(512L));
            verify(quotaService).recordVersionUpload(any(SharedFolderEntity.class), nullable(Long.class), eq(512L), eq(USER_ID));
        }

        @Test
        @DisplayName("異常系: ファイルが存在しないでFILE_SHARING_002例外")
        void バージョン作成_ファイル不在_FILE_SHARING_002例外() {
            // Given
            CreateVersionRequest request = new CreateVersionRequest(
                    "files/new.pdf", 1024L, "application/pdf", "コメント");
            given(fileService.findFileOrThrow(FILE_ID))
                    .willThrow(new BusinessException(FileSharingErrorCode.FILE_NOT_FOUND));

            // When / Then
            assertThatThrownBy(() -> sharedFileVersionService.createVersion(FILE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FILE_SHARING_002"));
        }

        @Test
        @DisplayName("正常系: 既存バージョン3のファイルにバージョン4が作成される")
        void バージョン作成_バージョン番号インクリメント_正しい番号() {
            // Given
            CreateVersionRequest request = new CreateVersionRequest(
                    "files/v4.pdf", 4096L, "application/pdf", "第4版");
            SharedFileEntity fileEntity = createFileEntity(3);
            SharedFolderEntity folder = buildFolder();
            SharedFileVersionEntity savedVersion = createVersionEntity(4);
            FileVersionResponse response = createVersionResponse(4);

            given(fileService.findFileOrThrow(FILE_ID)).willReturn(fileEntity);
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder);
            willDoNothing().given(quotaService).checkFileQuota(any(SharedFolderEntity.class), eq(4096L));
            given(versionRepository.save(any(SharedFileVersionEntity.class))).willReturn(savedVersion);
            given(fileSharingMapper.toVersionResponse(savedVersion)).willReturn(response);

            // When
            FileVersionResponse result = sharedFileVersionService.createVersion(FILE_ID, USER_ID, request);

            // Then
            assertThat(result.getVersionNumber()).isEqualTo(4);
            assertThat(fileEntity.getCurrentVersion()).isEqualTo(4);
        }
    }
}
