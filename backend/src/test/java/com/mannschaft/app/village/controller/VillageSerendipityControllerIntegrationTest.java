package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageSerendipityRankingResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageSerendipityScoreEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageSerendipityScoreRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F17.1 Phase 3-β — VillageSerendipityController 統合テスト（認可根治戦役 Wave7・村ドメイン）。
 *
 * <p>{@code GET /api/v1/villages/{villageId}/serendipity-scores/ranking} は、他ユーザーの
 * {@code userId} とスコアを含むため、村人（現役メンバー）以外への開放は情報漏えいとなる。
 * 本テストは非村人・他村管理者（BOLA）からの到達を拒否し、村人・HEADMAN からは
 * 正常に閲覧できることを確認する。</p>
 */
@DisplayName("VillageSerendipityController 統合テスト（F17.1 Phase 3-β）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageSerendipityControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageSerendipityController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageSerendipityScoreRepository serendipityScoreRepository;

    private static final Long HEADMAN_USER_ID = 9_720_001L;
    private static final Long VILLAGER_USER_ID = 9_720_002L;
    private static final Long OTHER_USER_ID = 9_720_003L;
    private static final Long REGULAR_USER_ID = 9_720_004L;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/{villageId}/serendipity-scores/ranking
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET ranking — 村人（非管理者メンバー）は 200 でランキングを取得できる")
    void ranking_villager_ok() {
        authenticateAs(VILLAGER_USER_ID);
        VillageEntity v = persistVillage();
        persistVillager(v.getId(), VILLAGER_USER_ID);
        persistScore(v.getId(), VILLAGER_USER_ID, 3L, 30L);
        persistScore(v.getId(), OTHER_USER_ID, 1L, 10L);

        ApiResponse<VillageSerendipityRankingResponse> res = controller.getRanking(v.getId(), 10);

        assertThat(res.getData().total()).isEqualTo(2L);
        assertThat(res.getData().items()).hasSize(2);
    }

    @Test
    @DisplayName("GET ranking — HEADMAN も 200 でランキングを取得できる（正当な権限保持者）")
    void ranking_headman_ok() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);
        persistScore(v.getId(), HEADMAN_USER_ID, 5L, 50L);

        ApiResponse<VillageSerendipityRankingResponse> res = controller.getRanking(v.getId(), 10);

        assertThat(res.getData().total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("GET ranking — 非村人は VILLAGE_007（NOT_MEMBER）で拒否")
    void ranking_nonMember_forbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        persistScore(v.getId(), OTHER_USER_ID, 1L, 10L);
        // REGULAR_USER_ID は当該村のメンバーシップを持たない

        assertThatThrownBy(() -> controller.getRanking(v.getId(), 10))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    @Test
    @DisplayName("GET ranking — 別村の HEADMAN であっても当該村の非会員なら VILLAGE_007（BOLA 対策）")
    void ranking_headmanOfOtherVillage_forbidden() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity other = persistVillage();
        persistHeadman(other.getId(), HEADMAN_USER_ID);
        VillageEntity target = persistVillage();
        persistScore(target.getId(), OTHER_USER_ID, 1L, 10L);
        // HEADMAN_USER_ID は target 村には所属していない

        assertThatThrownBy(() -> controller.getRanking(target.getId(), 10))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("vs-" + Long.toHexString(System.nanoTime()))
                .name("ご縁テスト村" + System.nanoTime())
                .description("Serendipity test village")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_USER_ID)
                .build();
        return villageRepository.saveAndFlush(v);
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

    private VillageMembershipEntity persistVillager(UUID villageId, Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    private VillageSerendipityScoreEntity persistScore(UUID villageId, Long userId,
                                                       long encounterCount, long interactionScore) {
        VillageSerendipityScoreEntity e = VillageSerendipityScoreEntity.builder()
                .villageId(villageId)
                .userId(userId)
                .encounterCount(encounterCount)
                .interactionScore(interactionScore)
                .lastUpdatedAt(LocalDateTime.now())
                .build();
        return serendipityScoreRepository.saveAndFlush(e);
    }
}
