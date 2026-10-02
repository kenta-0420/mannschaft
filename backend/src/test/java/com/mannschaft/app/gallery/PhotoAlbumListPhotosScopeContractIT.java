package com.mannschaft.app.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB1b — gallery ドメイン {@code PhotoAlbumController.listPhotos}
 * （写真一覧・閲覧系）+ {@code getMediaUploadUrl}（追加確認）API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: 依頼文（Wave3-B1b gallery節）。{@code PhotoService#listPhotos} は従来
 * 可視性検証ゼロで {@code photoRepository.findByAlbumId} を返すだけだった（対照的に
 * {@code getAlbum} は {@code ContentVisibilityChecker.assertCanView} を通していた）。
 * entity 由来 scope（アルバムの teamId/organizationId）の {@code AccessControlService#checkMembership}
 * で保護する（同ドメイン既存 {@code getAlbumDownloadUrl}/{@code getPhotoDownloadUrl} と同じ手本）。</p>
 *
 * <p>金型: {@code GalleryScopeContractIT}（Wave3-B5・{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL）。gallery は ID-only エンドポイントのため、越境は 404 ではなく 403 に畳み込まれる
 * （{@code GalleryScopeContractIT} の updateAlbum/deleteAlbum と同じ既存パターンを listPhotos にも適用。
 * path に scope が無く entity 由来判定のため BOLA 存在秘匿は不要 — 非会員には album 自体の存在を
 * 教えない設計は行わず、単に 403 で拒否する）。</p>
 *
 * <p><b>追加確認（依頼文）:</b> {@code getMediaUploadUrl}（POST /{id}/media/upload-url）は
 * {@code GalleryMediaUploadService#generateUploadUrl} が uploadPhotos と同一の
 * checkMembership + canUpload ポリシーを既に実装済みであることを確認する回帰テストとして含める
 * （本 IT のために新規実装した認可ではない）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("gallery ドメイン listPhotos/getMediaUploadUrl 認可契約テスト（試練・Wave3-B1b）")
class PhotoAlbumListPhotosScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PhotoAlbumRepository photoAlbumRepository;

    @PersistenceContext
    private EntityManager em;

    /** R2 は外部依存のため mock（getMediaUploadUrl の Presigned URL 発行で使用）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    private Long albumTeamAId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("LP認可契約チームA");
        teamBId = insertTeam("LP認可契約チームB");

        adminAId = insertUser("lp-authz-admin-a@example.com");
        adminBId = insertUser("lp-authz-admin-b@example.com");
        memberAId = insertUser("lp-authz-member-a@example.com");
        outsiderId = insertUser("lp-authz-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はチームA/Bどちらにも所属しない。

        albumTeamAId = photoAlbumRepository.save(PhotoAlbumEntity.builder()
                .teamId(teamAId).title("LP認可契約テストアルバム")
                .allowMemberUpload(false).allowDownload(true)
                .build()).getId();

        em.flush();
        em.clear();

        given(r2StorageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willReturn(new PresignedUploadResult("https://r2.example.com/upload-dummy", "dummy-key", 600));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 写真一覧(listPhotos)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("写真一覧(listPhotos)")
    class ListPhotos {

        @Test
        @DisplayName("非メンバーの写真一覧取得は403（checkMembership）")
        void 非メンバーの写真一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/gallery/albums/{id}/photos", albumTeamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの写真一覧取得は403（entity由来scopeで認可判定・ID-onlyのため404ではなく403）")
        void 他チームADMINの写真一覧は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/gallery/albums/{id}/photos", albumTeamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバー(非ADMIN)の写真一覧取得は200")
        void 正当メンバーの写真一覧は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/gallery/albums/{id}/photos", albumTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINの写真一覧取得は200")
        void 正当ADMINの写真一覧は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/gallery/albums/{id}/photos", albumTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("不在アルバムの写真一覧取得は404（GALLERY_001）")
        void 不在アルバムの写真一覧は404() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/gallery/albums/{id}/photos", 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("GALLERY_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // メディアアップロード Presigned URL 発行(getMediaUploadUrl) — 追加確認（既存実装済みの回帰確認）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メディアアップロードURL発行(getMediaUploadUrl) — 追加確認")
    class GetMediaUploadUrl {

        @Test
        @DisplayName("非メンバーのURL発行は403（checkMembership・既存実装済み）")
        void 非メンバーのURL発行は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/gallery/albums/{id}/media/upload-url", albumTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(uploadUrlBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバー(allowMemberUpload=false)のURL発行は403（GALLERY_007・既存実装済み）")
        void 一般メンバーのURL発行はallowMemberUploadFalseで403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/gallery/albums/{id}/media/upload-url", albumTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(uploadUrlBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("GALLERY_007"));
        }

        @Test
        @DisplayName("正当ADMINのURL発行は200（既存実装済み）")
        void 正当ADMINのURL発行は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/gallery/albums/{id}/media/upload-url", albumTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(uploadUrlBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.uploadUrl").exists());
        }

        private Map<String, Object> uploadUrlBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mediaType", "PHOTO");
            body.put("contentType", "image/jpeg");
            // fileSize は意図的に省略（クォータチェックをスキップし、storage_plans/storage_subscriptions の
            // シード不要にする・本 IT の関心は認可のみ）。
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'LP契約', 'テスト', 'LP契約テスト', 'ACTIVE', "
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
                                + "CONCAT('lp-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
