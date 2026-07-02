package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.SharedFileDownloadUrlResponse;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFileQuotaService;
import com.mannschaft.app.filesharing.service.SharedFileService;
import com.mannschaft.app.filesharing.service.SharedFolderQueryService;
import com.mannschaft.app.filesharing.service.SharedFolderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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
import static org.mockito.Mockito.inOrder;
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

    /** F08.7.1 / 04: 大会フォルダ横断認可ゲート。大会以外（TEAM 等）の本テストでは no-op。 */
    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;

    /** download-url 発行時のフォルダスコープ別閲覧認可（漏洩防止の核）。 */
    @Mock
    private SharedFolderQueryService folderQueryService;

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
                    FOLDER_ID, "test.pdf", "files/test.pdf", 1024L, "application/pdf", null, null, null);

            SharedFolderEntity folder = buildFolder();
            SharedFileEntity savedFile = SharedFileEntity.builder()
                    .folderId(FOLDER_ID).name("test.pdf").fileKey("files/test.pdf")
                    .fileSize(1024L).contentType("application/pdf").createdBy(USER_ID).build();
            FileResponse response = new FileResponse(FILE_ID, FOLDER_ID, "test.pdf", "files/test.pdf",
                    1024L, "application/pdf", null, USER_ID, 1, null, null, null, null);

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
                    FOLDER_ID, "big.pdf", "files/big.pdf", 999999L, "application/pdf", null, null, null);
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

    // ========================================
    // presignDownload（ダウンロード URL 発行 + 認可）
    // ========================================

    @Nested
    @DisplayName("presignDownload")
    class PresignDownload {

        private static final Long FOLDER_OF_FILE = 7L;
        private static final String FILE_KEY = "files/TEAM/5/abc-uuid.pdf";

        private SharedFileEntity buildFile() {
            return SharedFileEntity.builder()
                    .folderId(FOLDER_OF_FILE).name("doc.pdf").fileKey(FILE_KEY)
                    .fileSize(2048L).contentType("application/pdf").createdBy(USER_ID).build();
        }

        @Test
        @DisplayName("AC-DL-1: 認可済みユーザーは downloadUrl（非空）を取得できる")
        void AC_DL_1_認可済み_downloadUrl取得() {
            // Given
            SharedFileEntity file = buildFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            willDoNothing().given(folderQueryService).authorizeDownload(FILE_ID, USER_ID);
            given(r2StorageService.generateDownloadUrl(eq(FILE_KEY), any()))
                    .willReturn("https://r2.example.com/" + FILE_KEY + "?X-Amz-Signature=xxx");

            // When
            SharedFileDownloadUrlResponse result = sharedFileService.presignDownload(FILE_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.downloadUrl()).isNotBlank().contains(FILE_KEY);
            assertThat(result.expiresInSeconds()).isNotNull().isPositive();
        }

        @Test
        @DisplayName("AC-DL-3: 他人の個人フォルダ配下のファイルは 404（FOLDER_NOT_FOUND・存在隠蔽）でURL未発行")
        void AC_DL_3_他人の個人フォルダ_404_URL未発行() {
            // Given
            SharedFileEntity file = buildFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            // PERSONAL 本人不一致は authorizeFolderViewById が FOLDER_NOT_FOUND（→404）を投げる
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderQueryService).authorizeDownload(FILE_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.presignDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
            // 認可で弾かれた場合は URL を一切発行しない（漏洩防止）
            verify(r2StorageService, never()).generateDownloadUrl(any(), any());
        }

        @Test
        @DisplayName("AC-DL-4: 非所属チーム/組織のファイルは 403（COMMON_002）でURL未発行")
        void AC_DL_4_非所属チーム_403_URL未発行() {
            // Given
            SharedFileEntity file = buildFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            // TEAM/ORG 非メンバーは authorizeFolderViewById（内部 checkMembership）が COMMON_002（→403）を投げる
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderQueryService).authorizeDownload(FILE_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.presignDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(r2StorageService, never()).generateDownloadUrl(any(), any());
        }

        @Test
        @DisplayName("AC-DL-5: 存在しない fileId は 404（FILE_NOT_FOUND・400/500でない）")
        void AC_DL_5_存在しないファイル_404() {
            // Given
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sharedFileService.presignDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND));
            // 存在しなければ認可も URL 発行も行わない
            verify(folderQueryService, never()).authorizeDownload(anyLong(), anyLong());
            verify(r2StorageService, never()).generateDownloadUrl(any(), any());
        }

        @Test
        @DisplayName("AC-DL-6: generateDownloadUrl に正しい fileKey を渡す")
        void AC_DL_6_正しいfileKeyを渡す() {
            // Given
            SharedFileEntity file = buildFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            willDoNothing().given(folderQueryService).authorizeDownload(FILE_ID, USER_ID);
            given(r2StorageService.generateDownloadUrl(eq(FILE_KEY), any())).willReturn("https://r2/x");

            // When
            sharedFileService.presignDownload(FILE_ID, USER_ID);

            // Then: file.getFileKey() がそのまま presign に渡る
            verify(r2StorageService).generateDownloadUrl(eq(FILE_KEY), any());
        }

        @Test
        @DisplayName("AC-C1相当: DL 禁止(authorizeDownload が DOWNLOAD_DISABLED)なら 403 で URL 未発行（generateDownloadUrl を呼ばない）")
        void AC_C_DL禁止_403_URL未発行() {
            // Given
            SharedFileEntity file = buildFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            willThrow(new BusinessException(FileSharingErrorCode.DOWNLOAD_DISABLED))
                    .given(folderQueryService).authorizeDownload(FILE_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.presignDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
            // DL 禁止で弾かれたら presigned URL を一切発行しない。
            verify(r2StorageService, never()).generateDownloadUrl(any(), any());
        }
    }

    // ========================================
    // listFilesPaged / listFiles（一覧の IDOR 封鎖）
    // ========================================

    @Nested
    @DisplayName("listFilesPaged / listFiles — IDOR 封鎖")
    class ListFilesAuthorization {

        @Test
        @DisplayName("AC-A1相当: TEAM 非会員は 403（COMMON_002）で fileRepository を引かない")
        void listFilesPaged_非会員_403_リポジトリ未参照() {
            // Given: authorizeFolderViewById が内部 checkMembership で COMMON_002（→403）を投げる
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderQueryService).authorizeFolderViewById(FOLDER_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.listFilesPaged(
                    FOLDER_ID, USER_ID, PageRequest.of(0, 20)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            // 認可で弾かれた場合はファイルを一切引かない（メタ漏洩の封鎖）
            verify(fileRepository, never()).findByFolderIdOrderByNameAsc(anyLong(), any());
            verify(fileRepository, never()).findByFolderIdOrderByNameAsc(anyLong());
        }

        @Test
        @DisplayName("AC-A2相当: 他人の PERSONAL フォルダは 404（FOLDER_NOT_FOUND・存在隠蔽）で未参照")
        void listFilesPaged_他人PERSONAL_404_リポジトリ未参照() {
            // Given
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderQueryService).authorizeFolderViewById(FOLDER_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.listFilesPaged(
                    FOLDER_ID, USER_ID, PageRequest.of(0, 20)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
            verify(fileRepository, never()).findByFolderIdOrderByNameAsc(anyLong(), any());
        }

        @Test
        @DisplayName("AC-A4相当: 正規会員は認可通過後にページを取得できる（回帰なし）")
        void listFilesPaged_正規会員_200相当() {
            // Given
            willDoNothing().given(folderQueryService).authorizeFolderViewById(FOLDER_ID, USER_ID);
            given(fileRepository.findByFolderIdOrderByNameAsc(eq(FOLDER_ID), any()))
                    .willReturn(Page.empty());

            // When
            Page<FileResponse> result = sharedFileService.listFilesPaged(
                    FOLDER_ID, USER_ID, PageRequest.of(0, 20));

            // Then: 認可が先に呼ばれ、その後にリポジトリを引く
            assertThat(result).isNotNull();
            InOrder order = inOrder(folderQueryService, fileRepository);
            order.verify(folderQueryService).authorizeFolderViewById(FOLDER_ID, USER_ID);
            order.verify(fileRepository).findByFolderIdOrderByNameAsc(eq(FOLDER_ID), any());
        }

        @Test
        @DisplayName("listFiles（内部用）も認可を先に通し、弾かれたらリポジトリ未参照")
        void listFiles_非会員_403_リポジトリ未参照() {
            // Given
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderQueryService).authorizeFolderViewById(FOLDER_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.listFiles(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(fileRepository, never()).findByFolderIdOrderByNameAsc(anyLong());
        }
    }

    // ========================================
    // getFile（詳細の IDOR 封鎖・順序: fileId 実在確認 → フォルダ認可）
    // ========================================

    @Nested
    @DisplayName("getFile — IDOR 封鎖")
    class GetFileAuthorization {

        private static final Long FOLDER_OF_FILE = 7L;

        private SharedFileEntity buildFile() {
            return SharedFileEntity.builder()
                    .folderId(FOLDER_OF_FILE).name("doc.pdf").fileKey("files/TEAM/5/x.pdf")
                    .fileSize(2048L).contentType("application/pdf").createdBy(USER_ID).build();
        }

        @Test
        @DisplayName("AC-A3相当: 他チームの fileId は 403（TEAM 非会員・COMMON_002）")
        void getFile_他チーム_403() {
            // Given
            SharedFileEntity file = buildFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderQueryService).authorizeFileViewById(FILE_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.getFile(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            // 認可で弾かれたらメタを返さない
            verify(fileSharingMapper, never()).toFileResponse(any());
        }

        @Test
        @DisplayName("AC-A3相当: 他人 PERSONAL 配下の fileId は 404（FOLDER_NOT_FOUND・存在隠蔽）")
        void getFile_他人PERSONAL_404() {
            // Given
            SharedFileEntity file = buildFile();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderQueryService).authorizeFileViewById(FILE_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> sharedFileService.getFile(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
            verify(fileSharingMapper, never()).toFileResponse(any());
        }

        @Test
        @DisplayName("存在しない fileId は 404（FILE_NOT_FOUND）で認可を呼ばない（順序: 実在確認が先）")
        void getFile_不存在_404_認可未呼び出し() {
            // Given
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sharedFileService.getFile(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND));
            // 実在確認（404）が先。存在しないファイルではフォルダ認可を呼ばない
            verify(folderQueryService, never()).authorizeFileViewById(anyLong(), anyLong());
        }

        @Test
        @DisplayName("AC-A4相当: 正規会員は 実在確認 → 認可 の順を経てメタを取得できる")
        void getFile_正規会員_200相当() {
            // Given
            SharedFileEntity file = buildFile();
            FileResponse response = new FileResponse(FILE_ID, FOLDER_OF_FILE, "doc.pdf",
                    "files/TEAM/5/x.pdf", 2048L, "application/pdf", null, USER_ID, 1, null, null, null, null);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            willDoNothing().given(folderQueryService).authorizeFileViewById(FILE_ID, USER_ID);
            given(fileSharingMapper.toFileResponse(file)).willReturn(response);

            // When
            FileResponse result = sharedFileService.getFile(FILE_ID, USER_ID);

            // Then: fileId 実在確認 → フォルダ認可 の順序
            assertThat(result).isNotNull();
            InOrder order = inOrder(fileRepository, folderQueryService, fileSharingMapper);
            order.verify(fileRepository).findById(FILE_ID);
            order.verify(folderQueryService).authorizeFileViewById(FILE_ID, USER_ID);
            order.verify(fileSharingMapper).toFileResponse(file);
        }
    }
}
