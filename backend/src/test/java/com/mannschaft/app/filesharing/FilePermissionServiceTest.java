package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreatePermissionRequest;
import com.mannschaft.app.filesharing.dto.PermissionResponse;
import com.mannschaft.app.filesharing.entity.FilePermissionEntity;
import com.mannschaft.app.filesharing.repository.FilePermissionRepository;
import com.mannschaft.app.filesharing.service.FilePermissionService;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FilePermissionService} の単体テスト。
 * ファイル・フォルダに対するアクセス権限の一覧取得・付与・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FilePermissionService 単体テスト")
class FilePermissionServiceTest {

    @Mock
    private FilePermissionRepository permissionRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;

    @InjectMocks
    private FilePermissionService filePermissionService;

    private static final Long TARGET_ID = 100L;
    private static final Long PERMISSION_ID = 1L;
    private static final Long PERMISSION_TARGET_ID = 10L;
    private static final String TARGET_TYPE = "FILE";

    private FilePermissionEntity createPermissionEntity() {
        return FilePermissionEntity.builder()
                .targetType(TARGET_TYPE)
                .targetId(TARGET_ID)
                .permissionType(PermissionType.READ)
                .permissionTargetType(PermissionTargetType.USER)
                .permissionTargetId(PERMISSION_TARGET_ID)
                .build();
    }

    private PermissionResponse createPermissionResponse() {
        return new PermissionResponse(PERMISSION_ID, TARGET_TYPE, TARGET_ID,
                "READ", "USER", PERMISSION_TARGET_ID, LocalDateTime.now());
    }

    // ========================================
    // listPermissions
    // ========================================

    @Nested
    @DisplayName("listPermissions")
    class ListPermissions {

        @Test
        @DisplayName("正常系: 権限一覧が返る")
        void 権限一覧取得_正常_リスト返却() {
            // Given
            FilePermissionEntity entity = createPermissionEntity();
            PermissionResponse response = createPermissionResponse();
            given(permissionRepository.findByTargetTypeAndTargetId(TARGET_TYPE, TARGET_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toPermissionResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<PermissionResponse> result = filePermissionService.listPermissions(TARGET_TYPE, TARGET_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTargetType()).isEqualTo(TARGET_TYPE);
            assertThat(result.get(0).getPermissionType()).isEqualTo("READ");
        }

        @Test
        @DisplayName("正常系: 権限が存在しない場合は空リスト")
        void 権限一覧取得_権限なし_空リスト() {
            // Given
            given(permissionRepository.findByTargetTypeAndTargetId(TARGET_TYPE, TARGET_ID))
                    .willReturn(List.of());
            given(fileSharingMapper.toPermissionResponseList(List.of()))
                    .willReturn(List.of());

            // When
            List<PermissionResponse> result = filePermissionService.listPermissions(TARGET_TYPE, TARGET_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // createPermission
    // ========================================

    @Nested
    @DisplayName("createPermission")
    class CreatePermission {

        @Test
        @DisplayName("正常系: 権限が作成される")
        void 権限作成_正常_レスポンス返却() {
            // Given
            CreatePermissionRequest request = new CreatePermissionRequest(
                    TARGET_TYPE, TARGET_ID, "READ", "USER", PERMISSION_TARGET_ID);
            FilePermissionEntity savedEntity = createPermissionEntity();
            PermissionResponse response = createPermissionResponse();

            given(permissionRepository.save(any(FilePermissionEntity.class))).willReturn(savedEntity);
            given(fileSharingMapper.toPermissionResponse(savedEntity)).willReturn(response);

            // When
            PermissionResponse result = filePermissionService.createPermission(request);

            // Then
            assertThat(result.getTargetType()).isEqualTo(TARGET_TYPE);
            assertThat(result.getPermissionType()).isEqualTo("READ");
            verify(permissionRepository).save(any(FilePermissionEntity.class));
        }

        @Test
        @DisplayName("正常系: WRITE権限をTEAMに付与")
        void 権限作成_WRITE権限TEAM付与_レスポンス返却() {
            // Given
            CreatePermissionRequest request = new CreatePermissionRequest(
                    "FOLDER", 200L, "WRITE", "TEAM", 50L);
            FilePermissionEntity savedEntity = FilePermissionEntity.builder()
                    .targetType("FOLDER")
                    .targetId(200L)
                    .permissionType(PermissionType.WRITE)
                    .permissionTargetType(PermissionTargetType.TEAM)
                    .permissionTargetId(50L)
                    .build();
            PermissionResponse response = new PermissionResponse(
                    2L, "FOLDER", 200L, "WRITE", "TEAM", 50L, LocalDateTime.now());

            given(permissionRepository.save(any(FilePermissionEntity.class))).willReturn(savedEntity);
            given(fileSharingMapper.toPermissionResponse(savedEntity)).willReturn(response);

            // When
            PermissionResponse result = filePermissionService.createPermission(request);

            // Then
            assertThat(result.getTargetType()).isEqualTo("FOLDER");
            assertThat(result.getPermissionType()).isEqualTo("WRITE");
            assertThat(result.getPermissionTargetType()).isEqualTo("TEAM");
        }

        @Test
        @DisplayName("異常系: 不正なPermissionTypeでIllegalArgumentException")
        void 権限作成_不正なPermissionType_例外() {
            // Given
            CreatePermissionRequest request = new CreatePermissionRequest(
                    TARGET_TYPE, TARGET_ID, "INVALID", "USER", PERMISSION_TARGET_ID);

            // When / Then
            assertThatThrownBy(() -> filePermissionService.createPermission(request))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系: 不正なPermissionTargetTypeでIllegalArgumentException")
        void 権限作成_不正なPermissionTargetType_例外() {
            // Given
            CreatePermissionRequest request = new CreatePermissionRequest(
                    TARGET_TYPE, TARGET_ID, "READ", "INVALID_TARGET", PERMISSION_TARGET_ID);

            // When / Then
            assertThatThrownBy(() -> filePermissionService.createPermission(request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ========================================
    // deletePermission
    // ========================================

    @Nested
    @DisplayName("deletePermission")
    class DeletePermission {

        @Test
        @DisplayName("正常系: 権限が削除される")
        void 権限削除_正常_削除実行() {
            // Given
            FilePermissionEntity entity = createPermissionEntity();
            given(permissionRepository.findById(PERMISSION_ID)).willReturn(Optional.of(entity));

            // When
            filePermissionService.deletePermission(PERMISSION_ID);

            // Then
            verify(permissionRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: 権限が存在しないでFILE_SHARING_004例外")
        void 権限削除_権限不在_FILE_SHARING_004例外() {
            // Given
            given(permissionRepository.findById(PERMISSION_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> filePermissionService.deletePermission(PERMISSION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FILE_SHARING_004"));
        }
    }
}
