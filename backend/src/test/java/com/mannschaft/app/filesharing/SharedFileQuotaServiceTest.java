package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaExceededException;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.service.SharedFileQuotaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFileQuotaService} の単体テスト。
 * F13 Phase 4-ε F05.5 ファイル共有クォータ統合を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileQuotaService 単体テスト")
class SharedFileQuotaServiceTest {

    @Mock
    private StorageQuotaService storageQuotaService;

    @InjectMocks
    private SharedFileQuotaService sharedFileQuotaService;

    private static final Long TEAM_ID = 5L;
    private static final Long ORG_ID = 3L;
    private static final Long USER_ID = 10L;
    private static final Long FILE_ID = 100L;
    private static final Long VERSION_ID = 200L;
    private static final Long ACTOR_ID = 10L;

    // ========================================
    // resolveScope
    // ========================================

    @Nested
    @DisplayName("resolveScope — スコープ解決")
    class ResolveScopeTests {

        @Test
        @DisplayName("TEAMフォルダ_TEAMスコープが返る")
        void TEAMフォルダ_TEAMスコープ() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM)
                    .teamId(TEAM_ID)
                    .name("チームフォルダ")
                    .build();

            SharedFileQuotaService.ScopeResolution scope = sharedFileQuotaService.resolveScope(folder);

            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.TEAM);
            assertThat(scope.scopeId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("ORGANIZATIONフォルダ_ORGANIZATIONスコープが返る")
        void ORGANIZATIONフォルダ_ORGANIZATIONスコープ() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.ORGANIZATION)
                    .organizationId(ORG_ID)
                    .name("組織フォルダ")
                    .build();

            SharedFileQuotaService.ScopeResolution scope = sharedFileQuotaService.resolveScope(folder);

            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.ORGANIZATION);
            assertThat(scope.scopeId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("PERSONALフォルダ_PERSONALスコープが返る")
        void PERSONALフォルダ_PERSONALスコープ() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.PERSONAL)
                    .userId(USER_ID)
                    .name("個人フォルダ")
                    .build();

            SharedFileQuotaService.ScopeResolution scope = sharedFileQuotaService.resolveScope(folder);

            assertThat(scope.scopeType()).isEqualTo(StorageScopeType.PERSONAL);
            assertThat(scope.scopeId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("TEAMフォルダでteamIdがnull_IllegalStateException")
        void TEAMフォルダでteamIdがnull_IllegalStateException() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM)
                    .teamId(null)
                    .name("不正フォルダ")
                    .build();

            assertThatThrownBy(() -> sharedFileQuotaService.resolveScope(folder))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TEAM folder has null teamId");
        }

        @Test
        @DisplayName("ORGANIZATIONフォルダでorganizationIdがnull_IllegalStateException")
        void ORGANIZATIONフォルダでorganizationIdがnull_IllegalStateException() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.ORGANIZATION)
                    .organizationId(null)
                    .name("不正フォルダ")
                    .build();

            assertThatThrownBy(() -> sharedFileQuotaService.resolveScope(folder))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ORGANIZATION folder has null organizationId");
        }

        @Test
        @DisplayName("PERSONALフォルダでuserIdがnull_IllegalStateException")
        void PERSONALフォルダでuserIdがnull_IllegalStateException() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.PERSONAL)
                    .userId(null)
                    .name("不正フォルダ")
                    .build();

            assertThatThrownBy(() -> sharedFileQuotaService.resolveScope(folder))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PERSONAL folder has null userId");
        }
    }

    // ========================================
    // checkFileQuota
    // ========================================

    @Nested
    @DisplayName("checkFileQuota")
    class CheckFileQuotaTests {

        @Test
        @DisplayName("クォータ範囲内_例外なし")
        void クォータ範囲内_例外なし() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID).name("f").build();

            willDoNothing().given(storageQuotaService)
                    .checkQuota(StorageScopeType.TEAM, TEAM_ID, 1024L);

            sharedFileQuotaService.checkFileQuota(folder, 1024L);

            verify(storageQuotaService).checkQuota(StorageScopeType.TEAM, TEAM_ID, 1024L);
        }

        @Test
        @DisplayName("クォータ超過_STORAGE_QUOTA_EXCEEDEDに変換される")
        void クォータ超過_STORAGE_QUOTA_EXCEEDEDに変換() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID).name("f").build();

            willThrow(new StorageQuotaExceededException(
                    StorageScopeType.TEAM, TEAM_ID, 999999L, 5000000000L, 5368709120L))
                    .given(storageQuotaService)
                    .checkQuota(StorageScopeType.TEAM, TEAM_ID, 999999L);

            assertThatThrownBy(() -> sharedFileQuotaService.checkFileQuota(folder, 999999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.STORAGE_QUOTA_EXCEEDED));
        }
    }

    // ========================================
    // recordFileUpload
    // ========================================

    @Nested
    @DisplayName("recordFileUpload")
    class RecordFileUploadTests {

        @Test
        @DisplayName("正常_recordUploadが呼ばれる")
        void 正常_recordUploadが呼ばれる() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID).name("f").build();

            sharedFileQuotaService.recordFileUpload(folder, FILE_ID, 1024L, ACTOR_ID);

            verify(storageQuotaService).recordUpload(
                    StorageScopeType.TEAM, TEAM_ID, 1024L,
                    StorageFeatureType.FILE_SHARING,
                    SharedFileQuotaService.REFERENCE_TYPE_FILE, FILE_ID, ACTOR_ID);
        }

        @Test
        @DisplayName("サイズ0以下_recordUploadを呼ばない")
        void サイズ0以下_recordUploadを呼ばない() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID).name("f").build();

            sharedFileQuotaService.recordFileUpload(folder, FILE_ID, 0L, ACTOR_ID);

            verify(storageQuotaService, org.mockito.Mockito.never())
                    .recordUpload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong());
        }
    }

    // ========================================
    // recordVersionUpload
    // ========================================

    @Nested
    @DisplayName("recordVersionUpload")
    class RecordVersionUploadTests {

        @Test
        @DisplayName("正常_recordUploadがshared_file_versionsで呼ばれる")
        void 正常_recordUploadがバージョン参照で呼ばれる() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.ORGANIZATION).organizationId(ORG_ID).name("f").build();

            sharedFileQuotaService.recordVersionUpload(folder, VERSION_ID, 2048L, ACTOR_ID);

            verify(storageQuotaService).recordUpload(
                    StorageScopeType.ORGANIZATION, ORG_ID, 2048L,
                    StorageFeatureType.FILE_SHARING,
                    SharedFileQuotaService.REFERENCE_TYPE_VERSION, VERSION_ID, ACTOR_ID);
        }
    }

    // ========================================
    // recordFileDeletion
    // ========================================

    @Nested
    @DisplayName("recordFileDeletion")
    class RecordFileDeletionTests {

        @Test
        @DisplayName("正常_recordDeletionが呼ばれる")
        void 正常_recordDeletionが呼ばれる() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.PERSONAL).userId(USER_ID).name("f").build();

            sharedFileQuotaService.recordFileDeletion(folder, FILE_ID, 512L, ACTOR_ID);

            verify(storageQuotaService).recordDeletion(
                    StorageScopeType.PERSONAL, USER_ID, 512L,
                    StorageFeatureType.FILE_SHARING,
                    SharedFileQuotaService.REFERENCE_TYPE_FILE, FILE_ID, ACTOR_ID);
        }

        @Test
        @DisplayName("サイズ0以下_recordDeletionを呼ばない")
        void サイズ0以下_recordDeletionを呼ばない() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(TEAM_ID).name("f").build();

            sharedFileQuotaService.recordFileDeletion(folder, FILE_ID, 0L, ACTOR_ID);

            verify(storageQuotaService, org.mockito.Mockito.never())
                    .recordDeletion(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong());
        }
    }
}
