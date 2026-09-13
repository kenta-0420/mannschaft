package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.acl.StorageAccessService;
import com.mannschaft.app.filesharing.dto.FileResponse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class SharedFileStorageAclReadTest {

    private static final Long FILE_ID = 100L;
    private static final Long FOLDER_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final String FILE_KEY = "files/TEAM/99/current.pdf";

    @Mock private SharedFileRepository fileRepository;
    @Mock private SharedFileVersionRepository versionRepository;
    @Mock private FileSharingMapper fileSharingMapper;
    @Mock private SharedFolderService folderService;
    @Mock private SharedFileQuotaService quotaService;
    @Mock private R2StorageService r2StorageService;
    @Mock private SharedFolderQueryService folderQueryService;
    @Mock private FolderScopeAccessGuard folderScopeAccessGuard;
    @Mock private com.mannschaft.app.common.storage.acl.StorageAclService storageAclService;
    @Mock private StorageAccessService storageAccessService;

    @InjectMocks private SharedFileService service;

    @Test
    void normalDownloadUsesAuthorizedEntityTupleAndHidesAclMismatch() {
        SharedFileEntity file = file(FILE_ID, FILE_KEY);
        given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
        willDoNothing().given(folderQueryService).authorizeDownload(FILE_ID, USER_ID);
        stubCurrentFile(file);
        willThrow(new BusinessException(StorageErrorCode.ACL_NOT_FOUND)).given(storageAccessService)
                .generateDownloadUrl(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.presignDownload(FILE_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(StorageErrorCode.ACL_NOT_FOUND));
    }

    @Test
    void listOmitsMissingOrCrossScopeAclEntries() {
        SharedFileEntity readable = file(FILE_ID, FILE_KEY);
        SharedFileEntity unreadable = file(101L, "files/TEAM/99/foreign.pdf");
        given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(readable, unreadable));
        given(folderQueryService.resolveVisibleFileLevels(FOLDER_ID, USER_ID)).willReturn(null);
        given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder());
        given(versionRepository.findByFileIdAndVersionNumber(FILE_ID, 1))
                .willReturn(Optional.of(version(FILE_ID, FILE_KEY, 200L)));
        given(versionRepository.findByFileIdAndVersionNumber(101L, 1))
                .willReturn(Optional.of(version(101L, unreadable.getFileKey(), 201L)));
        given(storageAccessService.generateDownloadUrlsForList(any(), any()))
                .willReturn(Map.of(FILE_KEY, "https://storage.example/readable"));
        given(fileSharingMapper.toFileResponseList(any())).willReturn(List.of(response()));

        List<FileResponse> result = service.listFiles(FOLDER_ID, USER_ID);

        assertThat(result).containsExactly(response());
    }

    @Test
    void publicLinkDownloadStillChecksCurrentFileFolderAndVersionBeforeAcl() {
        SharedFileEntity file = file(FILE_ID, FILE_KEY);
        given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
        willDoNothing().given(folderQueryService).checkDownloadDisabledForSharedLink(FILE_ID);
        stubCurrentFile(file);
        given(storageAccessService.generateDownloadUrl(any(), any(), any(), any(), any()))
                .willReturn("https://storage.example/current");

        assertThat(service.presignDownloadForSharedLink(FILE_ID).downloadUrl())
                .isEqualTo("https://storage.example/current");
    }

    private void stubCurrentFile(SharedFileEntity file) {
        given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(folder());
        given(versionRepository.findByFileIdAndVersionNumber(FILE_ID, 1))
                .willReturn(Optional.of(version(FILE_ID, file.getFileKey(), 200L)));
    }

    private SharedFolderEntity folder() {
        return SharedFolderEntity.builder().id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(99L).build();
    }

    private SharedFileEntity file(Long id, String key) {
        return SharedFileEntity.builder().id(id).folderId(FOLDER_ID).name("current.pdf").fileKey(key)
                .fileSize(1L).contentType("application/pdf").createdBy(USER_ID).currentVersion(1).build();
    }

    private SharedFileVersionEntity version(Long fileId, String key, Long versionId) {
        return SharedFileVersionEntity.builder().id(versionId).fileId(fileId).versionNumber(1).fileKey(key)
                .fileSize(1L).contentType("application/pdf").uploadedBy(USER_ID).build();
    }

    private FileResponse response() {
        return new FileResponse(FILE_ID, FOLDER_ID, "current.pdf", FILE_KEY, 1L, "application/pdf",
                null, USER_ID, 1, null, null, null, null);
    }
}
