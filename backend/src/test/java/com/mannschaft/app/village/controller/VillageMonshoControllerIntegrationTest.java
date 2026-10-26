package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageMonshoResponse;
import com.mannschaft.app.village.dto.VillageMonshoUpdateRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * F17 Phase 2 U7 — VillageMonshoController 統合テスト。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>PUT    /api/v1/villages/{villageId}/monsho — 村紋設定（HEADMAN/SYSTEM_ADMIN）</li>
 *   <li>DELETE /api/v1/villages/{villageId}/monsho — 村紋削除</li>
 * </ul>
 *
 * <p>Phase 2 シンプル版: 本 API は {@code villages.monsho_r2_key} カラム更新のみを担い、
 * R2 への実アップロードは別経路（プリサインド URL 発行 API 等）に委ねる。</p>
 */
@DisplayName("VillageMonshoController 統合テスト（F17 Phase 2 U7）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageMonshoControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageMonshoController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    private static final Long ADMIN_USER_ID = 9_720_001L;
    private static final Long HEADMAN_USER_ID = 9_720_002L;
    private static final Long REGULAR_USER_ID = 9_720_003L;

    @BeforeEach
    void setUp() {
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
    // PUT /api/v1/villages/{villageId}/monsho
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PUT — HEADMAN が r2Key を設定 → 200 + DB が更新")
    void update_headman_ok() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity village = persistVillage();
        persistHeadman(village.getId(), HEADMAN_USER_ID);
        String newKey = "village/" + village.getId() + "/monsho/test-monsho.png";

        ResponseEntity<ApiResponse<VillageMonshoResponse>> res = controller.update(
                village.getId(), new VillageMonshoUpdateRequest(newKey));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        VillageMonshoResponse body = res.getBody().getData();
        assertThat(body.villageId()).isEqualTo(village.getId());
        assertThat(body.monshoR2Key()).isEqualTo(newKey);
        VillageEntity reloaded = villageRepository.findById(village.getId()).orElseThrow();
        assertThat(reloaded.getMonshoR2Key()).isEqualTo(newKey);
    }

    @Test
    @DisplayName("PUT — 一般ユーザーは VILLAGE_024 MODERATION_FORBIDDEN")
    void update_regular_forbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity village = persistVillage();
        VillageMonshoUpdateRequest req = new VillageMonshoUpdateRequest(
                "village/" + village.getId() + "/monsho/x.png");

        assertThatThrownBy(() -> controller.update(village.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/villages/{villageId}/monsho
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE — HEADMAN が削除 → 200 + DB の monsho_r2_key が NULL")
    void delete_headman_ok() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity village = persistVillageWithMonsho("village/seed/monsho/seed.png");
        persistHeadman(village.getId(), HEADMAN_USER_ID);

        ResponseEntity<ApiResponse<VillageMonshoResponse>> res = controller.delete(village.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        VillageMonshoResponse body = res.getBody().getData();
        assertThat(body.monshoR2Key()).isNull();
        VillageEntity reloaded = villageRepository.findById(village.getId()).orElseThrow();
        assertThat(reloaded.getMonshoR2Key()).isNull();
    }

    @Test
    @DisplayName("DELETE — SYSTEM_ADMIN は HEADMAN でなくても削除可能 → 200")
    void delete_systemAdmin_ok() {
        authenticateAs(ADMIN_USER_ID);
        VillageEntity village = persistVillageWithMonsho("village/seed/monsho/seed2.png");
        // HEADMAN を別人にしておく
        persistHeadman(village.getId(), HEADMAN_USER_ID);

        ResponseEntity<ApiResponse<VillageMonshoResponse>> res = controller.delete(village.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().monshoR2Key()).isNull();
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private VillageEntity persistVillage() {
        return persistVillageWithMonsho(null);
    }

    private VillageEntity persistVillageWithMonsho(String monshoR2Key) {
        VillageEntity e = VillageEntity.builder()
                .slug("vt-monsho-" + Long.toHexString(System.nanoTime()))
                .name("紋テスト村" + System.nanoTime())
                .description("村紋テスト用")
                .type(VillageType.OFFICIAL)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("業種")
                .monshoR2Key(monshoR2Key)
                .memberCountCache(0L)
                .createdByUserId(ADMIN_USER_ID)
                .build();
        return villageRepository.saveAndFlush(e);
    }

    private VillageMembershipEntity persistHeadman(java.util.UUID villageId, Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.HEADMAN)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }
}
