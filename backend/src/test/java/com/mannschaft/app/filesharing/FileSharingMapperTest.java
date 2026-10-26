package com.mannschaft.app.filesharing;

import com.mannschaft.app.filesharing.dto.CommentResponse;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.FileVersionResponse;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.dto.PermissionResponse;
import com.mannschaft.app.filesharing.dto.StarResponse;
import com.mannschaft.app.filesharing.dto.TagResponse;
import com.mannschaft.app.filesharing.entity.FilePermissionEntity;
import com.mannschaft.app.filesharing.entity.SharedFileCommentEntity;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import com.mannschaft.app.filesharing.entity.SharedFileTagEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FileSharingMapper} の単体テスト。MapStruct生成実装を直接インスタンス化して検証する。
 */
@DisplayName("FileSharingMapper 単体テスト")
class FileSharingMapperTest {

    private final FileSharingMapper mapper = new FileSharingMapperImpl();

    // ========================================
    // toFolderResponse
    // ========================================

    @Nested
    @DisplayName("toFolderResponse")
    class ToFolderResponse {

        @Test
        @DisplayName("正常系: TEAM スコープのフォルダエンティティ → DTOに変換される")
        void チームフォルダ_DTO変換_正常() {
            SharedFolderEntity entity = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM)
                    .teamId(1L)
                    .name("テストフォルダ")
                    .description("説明")
                    .createdBy(10L)
                    .build();

            FolderResponse result = mapper.toFolderResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("テストフォルダ");
            assertThat(result.getDescription()).isEqualTo("説明");
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getCreatedBy()).isEqualTo(10L);
        }

        @Test
        @DisplayName("正常系: ORGANIZATION スコープのフォルダエンティティ → DTOに変換される")
        void 組織フォルダ_DTO変換_正常() {
            SharedFolderEntity entity = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.ORGANIZATION)
                    .organizationId(2L)
                    .name("組織フォルダ")
                    .createdBy(20L)
                    .build();

            FolderResponse result = mapper.toFolderResponse(entity);

            assertThat(result.getScopeType()).isEqualTo("ORGANIZATION");
        }

        @Test
        @DisplayName("正常系: PERSONAL スコープのフォルダエンティティ → DTOに変換される")
        void 個人フォルダ_DTO変換_正常() {
            SharedFolderEntity entity = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.PERSONAL)
                    .userId(5L)
                    .name("個人フォルダ")
                    .createdBy(5L)
                    .build();

            FolderResponse result = mapper.toFolderResponse(entity);

            assertThat(result.getScopeType()).isEqualTo("PERSONAL");
        }

        @Test
        @DisplayName("正常系: フォルダリスト変換")
        void フォルダリスト_DTO変換_正常() {
            SharedFolderEntity e1 = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(1L).name("フォルダ1").createdBy(10L).build();
            SharedFolderEntity e2 = SharedFolderEntity.builder()
                    .scopeType(FileScopeType.TEAM).teamId(1L).name("フォルダ2").createdBy(10L).build();

            List<FolderResponse> result = mapper.toFolderResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("フォルダ1");
        }

        @Test
        @DisplayName("正常系: 空リスト変換")
        void フォルダ空リスト_DTO変換_正常() {
            List<FolderResponse> result = mapper.toFolderResponseList(List.of());
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // toFileResponse
    // ========================================

    @Nested
    @DisplayName("toFileResponse")
    class ToFileResponse {

        @Test
        @DisplayName("正常系: ファイルエンティティ → DTOに変換される")
        void ファイルエンティティ_DTO変換_正常() {
            SharedFileEntity entity = SharedFileEntity.builder()
                    .folderId(1L)
                    .name("テスト.pdf")
                    .fileKey("uploads/test.pdf")
                    .fileSize(2048L)
                    .contentType("application/pdf")
                    .description("説明")
                    .createdBy(10L)
                    .build();

            FileResponse result = mapper.toFileResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("テスト.pdf");
            assertThat(result.getFileKey()).isEqualTo("uploads/test.pdf");
            assertThat(result.getFileSize()).isEqualTo(2048L);
            assertThat(result.getContentType()).isEqualTo("application/pdf");
            assertThat(result.getCreatedBy()).isEqualTo(10L);
        }

        @Test
        @DisplayName("正常系: ファイルリスト変換")
        void ファイルリスト_DTO変換_正常() {
            SharedFileEntity e1 = SharedFileEntity.builder()
                    .folderId(1L).name("f1.txt").fileKey("k1").fileSize(100L).contentType("text/plain").build();
            SharedFileEntity e2 = SharedFileEntity.builder()
                    .folderId(1L).name("f2.txt").fileKey("k2").fileSize(200L).contentType("text/plain").build();

            List<FileResponse> result = mapper.toFileResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toVersionResponse
    // ========================================

    @Nested
    @DisplayName("toVersionResponse")
    class ToVersionResponse {

        @Test
        @DisplayName("正常系: バージョンエンティティ → DTOに変換される")
        void バージョンエンティティ_DTO変換_正常() {
            SharedFileVersionEntity entity = SharedFileVersionEntity.builder()
                    .fileId(10L)
                    .versionNumber(2)
                    .fileKey("uploads/v2.pdf")
                    .fileSize(3072L)
                    .contentType("application/pdf")
                    .uploadedBy(100L)
                    .comment("第2版")
                    .build();

            FileVersionResponse result = mapper.toVersionResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getFileId()).isEqualTo(10L);
            assertThat(result.getVersionNumber()).isEqualTo(2);
            assertThat(result.getFileKey()).isEqualTo("uploads/v2.pdf");
            assertThat(result.getFileSize()).isEqualTo(3072L);
            assertThat(result.getComment()).isEqualTo("第2版");
        }

        @Test
        @DisplayName("正常系: バージョンリスト変換")
        void バージョンリスト_DTO変換_正常() {
            SharedFileVersionEntity e1 = SharedFileVersionEntity.builder()
                    .fileId(1L).versionNumber(1).fileKey("k1").fileSize(100L).contentType("text/plain").build();
            SharedFileVersionEntity e2 = SharedFileVersionEntity.builder()
                    .fileId(1L).versionNumber(2).fileKey("k2").fileSize(200L).contentType("text/plain").build();

            List<FileVersionResponse> result = mapper.toVersionResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toPermissionResponse
    // ========================================

    @Nested
    @DisplayName("toPermissionResponse")
    class ToPermissionResponse {

        @Test
        @DisplayName("正常系: READ/USER 権限エンティティ → DTOに変換される")
        void 権限エンティティ_DTO変換_READ_USER() {
            FilePermissionEntity entity = FilePermissionEntity.builder()
                    .targetType("FILE")
                    .targetId(10L)
                    .permissionType(PermissionType.READ)
                    .permissionTargetType(PermissionTargetType.USER)
                    .permissionTargetId(50L)
                    .build();

            PermissionResponse result = mapper.toPermissionResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getTargetType()).isEqualTo("FILE");
            assertThat(result.getTargetId()).isEqualTo(10L);
            assertThat(result.getPermissionType()).isEqualTo("READ");
            assertThat(result.getPermissionTargetType()).isEqualTo("USER");
            assertThat(result.getPermissionTargetId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("正常系: WRITE/ROLE 権限エンティティ → DTOに変換される")
        void 権限エンティティ_DTO変換_WRITE_ROLE() {
            FilePermissionEntity entity = FilePermissionEntity.builder()
                    .targetType("FOLDER")
                    .targetId(5L)
                    .permissionType(PermissionType.WRITE)
                    .permissionTargetType(PermissionTargetType.ROLE)
                    .permissionTargetId(1L)
                    .build();

            PermissionResponse result = mapper.toPermissionResponse(entity);

            assertThat(result.getPermissionType()).isEqualTo("WRITE");
            assertThat(result.getPermissionTargetType()).isEqualTo("ROLE");
        }

        @Test
        @DisplayName("正常系: 権限リスト変換")
        void 権限リスト_DTO変換_正常() {
            FilePermissionEntity e1 = FilePermissionEntity.builder()
                    .targetType("FILE").targetId(1L)
                    .permissionType(PermissionType.READ).permissionTargetType(PermissionTargetType.USER)
                    .permissionTargetId(10L).build();
            FilePermissionEntity e2 = FilePermissionEntity.builder()
                    .targetType("FILE").targetId(1L)
                    .permissionType(PermissionType.WRITE).permissionTargetType(PermissionTargetType.ROLE)
                    .permissionTargetId(20L).build();

            List<PermissionResponse> result = mapper.toPermissionResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toStarResponse
    // ========================================

    @Nested
    @DisplayName("toStarResponse")
    class ToStarResponse {

        @Test
        @DisplayName("正常系: スターエンティティ → DTOに変換される")
        void スターエンティティ_DTO変換_正常() {
            SharedFileStarEntity entity = SharedFileStarEntity.builder()
                    .fileId(10L)
                    .userId(50L)
                    .build();

            StarResponse result = mapper.toStarResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getFileId()).isEqualTo(10L);
            assertThat(result.getUserId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("正常系: スターリスト変換")
        void スターリスト_DTO変換_正常() {
            SharedFileStarEntity e1 = SharedFileStarEntity.builder().fileId(1L).userId(10L).build();
            SharedFileStarEntity e2 = SharedFileStarEntity.builder().fileId(1L).userId(20L).build();

            List<StarResponse> result = mapper.toStarResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toCommentResponse
    // ========================================

    @Nested
    @DisplayName("toCommentResponse")
    class ToCommentResponse {

        @Test
        @DisplayName("正常系: コメントエンティティ → DTOに変換される")
        void コメントエンティティ_DTO変換_正常() {
            SharedFileCommentEntity entity = SharedFileCommentEntity.builder()
                    .fileId(10L)
                    .userId(50L)
                    .body("コメント本文")
                    .build();

            CommentResponse result = mapper.toCommentResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getFileId()).isEqualTo(10L);
            assertThat(result.getUserId()).isEqualTo(50L);
            assertThat(result.getBody()).isEqualTo("コメント本文");
        }

        @Test
        @DisplayName("正常系: コメントリスト変換")
        void コメントリスト_DTO変換_正常() {
            SharedFileCommentEntity e1 = SharedFileCommentEntity.builder()
                    .fileId(1L).userId(10L).body("コメント1").build();
            SharedFileCommentEntity e2 = SharedFileCommentEntity.builder()
                    .fileId(1L).userId(20L).body("コメント2").build();

            List<CommentResponse> result = mapper.toCommentResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }

    // ========================================
    // toLinkResponse
    // ========================================

    @Nested
    @DisplayName("toLinkResponse")
    class ToLinkResponse {

        @Test
        @DisplayName("正常系: パスワードなしリンクエンティティ → hasPassword=false")
        void パスワードなしリンク_DTO変換_hasPasswordFalse() {
            SharedFileLinkEntity entity = SharedFileLinkEntity.builder()
                    .fileId(10L)
                    .token("abc123")
                    .expiresAt(LocalDateTime.of(2026, 12, 31, 0, 0))
                    .accessCount(5)
                    .createdBy(100L)
                    .build();

            LinkResponse result = mapper.toLinkResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getFileId()).isEqualTo(10L);
            assertThat(result.getToken()).isEqualTo("abc123");
            assertThat(result.isHasPassword()).isFalse();
            assertThat(result.getAccessCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("正常系: パスワードありリンクエンティティ → hasPassword=true")
        void パスワードありリンク_DTO変換_hasPasswordTrue() {
            SharedFileLinkEntity entity = SharedFileLinkEntity.builder()
                    .fileId(10L)
                    .token("xyz789")
                    .passwordHash("$2a$10$hashedpassword")
                    .accessCount(0)
                    .createdBy(100L)
                    .build();

            LinkResponse result = mapper.toLinkResponse(entity);

            assertThat(result.isHasPassword()).isTrue();
        }

        @Test
        @DisplayName("正常系: リンクリスト変換")
        void リンクリスト_DTO変換_正常() {
            SharedFileLinkEntity e1 = SharedFileLinkEntity.builder()
                    .fileId(1L).token("t1").accessCount(0).createdBy(10L).build();
            SharedFileLinkEntity e2 = SharedFileLinkEntity.builder()
                    .fileId(1L).token("t2").passwordHash("hash").accessCount(3).createdBy(10L).build();

            List<LinkResponse> result = mapper.toLinkResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).isHasPassword()).isFalse();
            assertThat(result.get(1).isHasPassword()).isTrue();
        }
    }

    // ========================================
    // toTagResponse
    // ========================================

    @Nested
    @DisplayName("toTagResponse")
    class ToTagResponse {

        @Test
        @DisplayName("正常系: タグエンティティ → DTOに変換される")
        void タグエンティティ_DTO変換_正常() {
            SharedFileTagEntity entity = SharedFileTagEntity.builder()
                    .fileId(10L)
                    .tagName("重要")
                    .userId(50L)
                    .build();

            TagResponse result = mapper.toTagResponse(entity);

            assertThat(result).isNotNull();
            assertThat(result.getFileId()).isEqualTo(10L);
            assertThat(result.getTagName()).isEqualTo("重要");
            assertThat(result.getUserId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("正常系: タグリスト変換")
        void タグリスト_DTO変換_正常() {
            SharedFileTagEntity e1 = SharedFileTagEntity.builder()
                    .fileId(1L).tagName("重要").userId(10L).build();
            SharedFileTagEntity e2 = SharedFileTagEntity.builder()
                    .fileId(1L).tagName("機密").userId(10L).build();

            List<TagResponse> result = mapper.toTagResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
        }
    }
}
