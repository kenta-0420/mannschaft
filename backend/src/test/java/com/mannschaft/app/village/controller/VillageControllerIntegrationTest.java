package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCreateRequest;
import com.mannschaft.app.village.dto.VillageResponse;
import com.mannschaft.app.village.dto.VillageSearchResponse;
import com.mannschaft.app.village.dto.VillageUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * F17.1 村本体 Controller 統合テスト（Phase 1）。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>POST   /api/v1/villages            — 村作成</li>
 *   <li>GET    /api/v1/villages/{id}       — 村詳細取得</li>
 *   <li>PATCH  /api/v1/villages/{id}       — 村更新</li>
 *   <li>DELETE /api/v1/villages/{id}       — 村論理削除</li>
 *   <li>POST   /api/v1/villages/{id}/archive — 村凍結</li>
 *   <li>GET    /api/v1/villages/search     — 村検索</li>
 * </ul>
 *
 * <h3>検証方針</h3>
 * <p>Controller を {@code @Autowired} し、Service〜Repository〜DB（Testcontainers MySQL）
 * まで実 Bean を通して検証する。SYSTEM_ADMIN 判定のみ {@code AbstractVillageIntegrationTest}
 * が {@link com.mannschaft.app.common.AccessControlService} をモック化している。</p>
 */
@DisplayName("VillageController 統合テスト（F17.1 Phase 1）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    private static final Long ADMIN_USER_ID = 9_700_001L;
    private static final Long REGULAR_USER_ID = 9_700_002L;
    private static final Long HEADMAN_USER_ID = 9_700_003L;

    @BeforeEach
    void setUp() {
        // デフォルトは全員 一般ユーザー扱い
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
        lenient().when(accessControlService.isSystemAdmin(ADMIN_USER_ID)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/villages
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /villages — SYSTEM_ADMIN は 201 で作成")
    void create_systemAdmin201() {
        authenticateAs(ADMIN_USER_ID);
        String slug = uniqueSlug();
        String name = uniqueName();

        VillageCreateRequest req = new VillageCreateRequest(
                slug, name, "テスト村",
                VillageType.OFFICIAL, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                "業種", null, null);

        ResponseEntity<ApiResponse<VillageResponse>> res = controller.create(req);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        VillageResponse body = res.getBody().getData();
        assertThat(body.slug()).isEqualTo(slug);
        assertThat(body.name()).isEqualTo(name);
        assertThat(body.type()).isEqualTo(VillageType.OFFICIAL);
        assertThat(villageRepository.existsBySlug(slug)).isTrue();
    }

    @Test
    @DisplayName("POST /villages — 一般ユーザーは VILLAGE_CREATE_FORBIDDEN")
    void create_regularUserForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageCreateRequest req = new VillageCreateRequest(
                uniqueSlug(), uniqueName(), null,
                VillageType.COMMUNITY, VillageJoinPolicy.FREE, VillageVisibility.PUBLIC,
                null, null, null);

        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_CREATE_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/{id}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /villages/{id} — PUBLIC 村は 200")
    void get_publicOk() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC);

        ResponseEntity<ApiResponse<VillageResponse>> res = controller.get(v.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().id()).isEqualTo(v.getId());
        assertThat(res.getBody().getData().isMember()).isFalse();
    }

    @Test
    @DisplayName("GET /villages/{id} — 存在しない ID は VILLAGE_NOT_FOUND")
    void get_notFound() {
        authenticateAs(REGULAR_USER_ID);
        UUID missing = UUID.fromString("01956cff-ffff-7000-8000-fffffffffffe");

        assertThatThrownBy(() -> controller.get(missing))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-1: GET /villages/{id} — UNLISTED 村は非村人だと VILLAGE_NOT_FOUND（不在と同一コード）")
    void get_unlistedForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.UNLISTED);

        assertThatThrownBy(() -> controller.get(v.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/villages/{id}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /villages/{id} — HEADMAN は更新 200")
    void update_headmanOk() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
        persistHeadman(v.getId(), HEADMAN_USER_ID);

        VillageUpdateRequest req = new VillageUpdateRequest(
                null, "更新後の説明", null, null, null, null, null, null, null);

        ResponseEntity<ApiResponse<VillageResponse>> res = controller.update(v.getId(), null, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().description()).isEqualTo("更新後の説明");
    }

    @Test
    @DisplayName("PATCH /villages/{id} — 非 HEADMAN は MODERATION_FORBIDDEN")
    void update_nonHeadmanForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC);

        VillageUpdateRequest req = new VillageUpdateRequest(
                null, "更新", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.update(v.getId(), null, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/villages/{id}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /villages/{id} — HEADMAN は 204")
    void delete_headmanNoContent() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
        persistHeadman(v.getId(), HEADMAN_USER_ID);

        ResponseEntity<Void> res = controller.delete(v.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        // 論理削除確認
        VillageEntity reloaded = villageRepository.findById(v.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("DELETE /villages/{id} — 非 HEADMAN は MODERATION_FORBIDDEN")
    void delete_nonHeadmanForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC);

        assertThatThrownBy(() -> controller.delete(v.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/villages/{id}/archive
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST /villages/{id}/archive — SYSTEM_ADMIN は 200")
    void archive_systemAdminOk() {
        authenticateAs(ADMIN_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC);

        ResponseEntity<Void> res = controller.archive(v.getId(), Map.of("reason", "ガイドライン違反"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        VillageEntity reloaded = villageRepository.findById(v.getId()).orElseThrow();
        assertThat(reloaded.getArchivedAt()).isNotNull();
    }

    @Test
    @DisplayName("POST /villages/{id}/archive — 一般ユーザーは MODERATION_FORBIDDEN")
    void archive_regularForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC);

        assertThatThrownBy(() -> controller.archive(v.getId(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/search
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /villages/search — PUBLIC 村が検索結果に出る")
    void search_findsPublic() {
        authenticateAs(REGULAR_USER_ID);
        String name = "整骨院テスト" + System.nanoTime();
        VillageEntity v = persistVillage(VillageVisibility.PUBLIC, name);

        ResponseEntity<VillageSearchResponse> res = controller.search("整骨院テスト", null, null, 0, 20);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().content()).extracting(VillageResponse::id).contains(v.getId());
    }

    @Test
    @DisplayName("GET /villages/search — UNLISTED 村は検索結果に出ない")
    void search_unlistedHidden() {
        authenticateAs(REGULAR_USER_ID);
        String name = "ULTest" + System.nanoTime();
        VillageEntity v = persistVillage(VillageVisibility.UNLISTED, name);

        ResponseEntity<VillageSearchResponse> res = controller.search(name, null, null, 0, 20);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().content()).extracting(VillageResponse::id).doesNotContain(v.getId());
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private VillageEntity persistVillage(VillageVisibility visibility) {
        return persistVillage(visibility, null);
    }

    private VillageEntity persistVillage(VillageVisibility visibility, String name) {
        String useName = name != null ? name : uniqueName();
        VillageEntity e = VillageEntity.builder()
                .slug(uniqueSlug())
                .name(useName)
                .description("テスト村説明")
                .type(VillageType.OFFICIAL)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(ADMIN_USER_ID)
                .build();
        return villageRepository.saveAndFlush(e);
    }

    private VillageMembershipEntity persistHeadman(UUID villageId, Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.HEADMAN)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    private static String uniqueSlug() {
        // 12文字のランダム英数字（ハイフンなし）— UUID 由来。SLUG_PATTERN ^[a-z0-9-]{3,40}$ に適合
        return "vt-" + Long.toHexString(System.nanoTime());
    }

    private static String uniqueName() {
        return "村テスト" + System.nanoTime();
    }
}
