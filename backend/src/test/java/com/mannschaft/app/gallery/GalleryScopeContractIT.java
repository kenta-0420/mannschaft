package com.mannschaft.app.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B5: gallery ドメイン（{@code PhotoAlbumController} /
 * {@code PhotoController}）の書込CRUD・DL 系 API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B5 gallery節）・{@code AccessControlService}
 * （{@code checkMembership}/{@code checkAdminOrAbove}）・{@code PhotoAlbumEntity.allowMemberUpload}
 * （member アップロード許可トグルの実効化）。金型: {@code DigestScopeContractIT}
 * （ID-only エンドポイントは entity 由来 scope で認可判定・403 で保護。gallery には
 * property のような scope-in-path が無いため、越境は 404 ではなく 403 に畳み込まれる）。</p>
 *
 * <p>対象（読取専用の一覧/詳細/写真一覧は {@code PhotoAlbumVisibilityResolver} 等
 * 既存の可視性層でカバー済みのため対象外。本 IT は「書込CRUD/ダウンロード」に限定）:</p>
 * <ul>
 *   <li>PhotoAlbumController: createAlbum/updateAlbum/deleteAlbum（管理）・
 *       uploadPhotos（メンバー必須 + ADMIN or allowMemberUpload=true）・
 *       downloadAlbum（メンバー必須のDL）</li>
 *   <li>PhotoController: updatePhoto/deletePhoto（管理）・downloadPhoto（メンバー必須のDL）</li>
 * </ul>
 *
 * <p>{@code GalleryMediaUploadService#generateUploadUrl}（presigned URL 発行）は
 * uploadPhotos と同一の認可ポリシーを共有するため、本 IT では直接の対象に含めない
 * （実装は同一ヘルパー {@code PhotoAlbumService.resolveScopeId/resolveScopeType} を使用）。</p>
 *
 * <p>R2StorageService は外部依存のため {@code @MockitoBean} でモックする
 * （DL URL発行・アルバム一括DLのZIPアップロードで使用）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("gallery ドメイン 書込CRUD/DL API 契約テスト（認可根治 Wave3-B5）")
class GalleryScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** R2 は外部依存のため mock（DL URL発行・ZIP アップロードで使用）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("GL認可契約チームA");
        teamBId = insertTeam("GL認可契約チームB");

        adminAId = insertUser("gl-authz-admin-a@example.com");
        adminBId = insertUser("gl-authz-admin-b@example.com");
        memberAId = insertUser("gl-authz-member-a@example.com");
        outsiderId = insertUser("gl-authz-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();

        given(r2StorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .willReturn("https://r2.example.com/download-dummy");
    }

    // ═════════════════════════════════════════════════════════════════════
    // アルバム作成(createAlbum)・更新(updateAlbum)・削除(deleteAlbum)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("アルバム作成(createAlbum)")
    class CreateAlbum {

        @Test
        @DisplayName("非ADMINメンバーの作成は403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの作成は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/gallery/albums")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createAlbumBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーの作成は403")
        void 非メンバーの作成は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/gallery/albums")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createAlbumBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/gallery/albums")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createAlbumBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    @Nested
    @DisplayName("アルバム更新(updateAlbum)・削除(deleteAlbum)")
    class UpdateAndDelete {

        @Test
        @DisplayName("非ADMINメンバーの更新は403")
        void 非ADMINメンバーの更新は403() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/gallery/albums/{id}", albumId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateAlbumBody("改題"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの更新は403（entity由来scopeで認可判定・ID-onlyのため404ではなく403）")
        void 他チームADMINの更新は403() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/gallery/albums/{id}", albumId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateAlbumBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/gallery/albums/{id}", albumId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateAlbumBody("改題済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("改題済"));
        }

        @Test
        @DisplayName("不在アルバムの更新は404（GALLERY_001）")
        void 不在アルバムの更新は404() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/gallery/albums/{id}", 999_999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateAlbumBody("改題"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("GALLERY_001"));
        }

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINメンバーの削除は403() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/gallery/albums/{id}", albumId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/gallery/albums/{id}", albumId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 写真アップロード(uploadPhotos) — allowMemberUpload トグルの実効化
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("写真アップロード(uploadPhotos)")
    class UploadPhotos {

        @Test
        @DisplayName("非メンバーのアップロードは403（checkMembership）")
        void 非メンバーのアップロードは403() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/gallery/albums/{id}/photos", albumId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(uploadPhotosBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(allowMemberUpload=false)のアップロードは403（GALLERY_007: 業務ルール拒否）")
        void 一般メンバーのアップロードはallowMemberUploadFalseで403() throws Exception {
            Long albumId = createAlbumAsAdminA(); // デフォルト allowMemberUpload=false

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/gallery/albums/{id}/photos", albumId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(uploadPhotosBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("GALLERY_007"));
        }

        @Test
        @DisplayName("一般メンバー(allowMemberUpload=true)のアップロードは201")
        void 一般メンバーのアップロードはallowMemberUploadTrueで201() throws Exception {
            Long albumId = createAlbumAsAdminA(true);

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/gallery/albums/{id}/photos", albumId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(uploadPhotosBody())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.uploadedCount").value(1));
        }

        @Test
        @DisplayName("ADMINは allowMemberUpload=false でもアップロード可（201）")
        void ADMINはallowMemberUploadFalseでもアップロード可() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/gallery/albums/{id}/photos", albumId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(uploadPhotosBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // アルバム一括DL(downloadAlbum)・写真個別DL(downloadPhoto)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("アルバム一括DL(downloadAlbum)・写真個別DL(downloadPhoto)")
    class Downloads {

        @Test
        @DisplayName("非メンバーのアルバム一括DLは403")
        void 非メンバーのアルバム一括DLは403() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/gallery/albums/{id}/download", albumId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバー(非ADMIN)のアルバム一括DLは200（DLはADMIN限定ではない）")
        void 正当メンバーのアルバム一括DLは200() throws Exception {
            Long albumId = createAlbumAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/gallery/albums/{id}/download", albumId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの写真個別DLは403")
        void 非メンバーの写真個別DLは403() throws Exception {
            Long albumId = createAlbumAsAdminA(true);
            Long photoId = uploadPhotoAsAdminA(albumId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/gallery/photos/{id}/download", photoId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバー(非ADMIN)の写真個別DLは200")
        void 正当メンバーの写真個別DLは200() throws Exception {
            Long albumId = createAlbumAsAdminA(true);
            Long photoId = uploadPhotoAsAdminA(albumId);

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/gallery/photos/{id}/download", photoId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 写真更新(updatePhoto)・削除(deletePhoto)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("写真更新(updatePhoto)・削除(deletePhoto)")
    class PhotoMutations {

        @Test
        @DisplayName("非ADMINメンバーの写真更新は403")
        void 非ADMINメンバーの写真更新は403() throws Exception {
            Long albumId = createAlbumAsAdminA(true);
            Long photoId = uploadPhotoAsAdminA(albumId);

            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("caption", "乗っ取りキャプション");
            mockMvc.perform(put("/api/v1/gallery/photos/{id}", photoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの写真更新は200")
        void 正当ADMINの写真更新は200() throws Exception {
            Long albumId = createAlbumAsAdminA();
            Long photoId = uploadPhotoAsAdminA(albumId);

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("caption", "更新後キャプション");
            mockMvc.perform(put("/api/v1/gallery/photos/{id}", photoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.caption").value("更新後キャプション"));
        }

        @Test
        @DisplayName("非ADMINメンバーの写真削除は403")
        void 非ADMINメンバーの写真削除は403() throws Exception {
            Long albumId = createAlbumAsAdminA(true);
            Long photoId = uploadPhotoAsAdminA(albumId);

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/gallery/photos/{id}", photoId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの写真削除は204")
        void 正当ADMINの写真削除は204() throws Exception {
            Long albumId = createAlbumAsAdminA();
            Long photoId = uploadPhotoAsAdminA(albumId);

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/gallery/photos/{id}", photoId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long createAlbumAsAdminA() throws Exception {
        return createAlbumAsAdminA(false);
    }

    /** ADMIN-A の認証コンテキストで正規のアルバムを 1 件作成し、その ID を返す。 */
    private Long createAlbumAsAdminA(boolean allowMemberUpload) throws Exception {
        setAuthentication(adminAId);
        Map<String, Object> body = createAlbumBody(teamAId);
        body.put("allowMemberUpload", allowMemberUpload);
        String resp = mockMvc.perform(post("/api/v1/gallery/albums")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** ADMIN-A の認証コンテキストで指定アルバムへ写真を 1 枚アップロードし、その photoId を返す。 */
    private Long uploadPhotoAsAdminA(Long albumId) throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/gallery/albums/{id}/photos", albumId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(uploadPhotosBody())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("photos").get(0).path("id").asLong();
    }

    private Map<String, Object> createAlbumBody(Long teamId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamId);
        body.put("title", "認可契約テストアルバム " + System.nanoTime());
        return body;
    }

    private Map<String, Object> updateAlbumBody(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        return body;
    }

    private Map<String, Object> uploadPhotosBody() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("r2Key", "gallery/TEAM/authz/photo-" + System.nanoTime() + ".jpg");
        item.put("originalFilename", "test.jpg");
        item.put("fileSize", 1024);
        item.put("contentType", "image/jpeg");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("photos", List.of(item));
        return body;
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'GL契約', 'テスト', 'GL契約テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('gl-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
