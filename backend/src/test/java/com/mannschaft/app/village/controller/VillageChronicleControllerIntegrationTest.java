package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.ChronicleResponse;
import com.mannschaft.app.village.entity.VillageChronicleEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageChronicleRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F17.1 Phase 3-β — VillageChronicleController 統合テスト（村史の閲覧認可・契約）。
 *
 * <p>村史は掲示板スレッドのタイトルを集計して TOP3 トピックとして公開するため、
 * 掲示板本体と同一の閲覧認可（{@code bulletin_visibility}）に従う必要がある。
 * 認可が無いと MEMBERS_ONLY 村のスレッドタイトルが村史経由で非メンバーに漏洩する。</p>
 *
 * <h3>受け入れ条件</h3>
 * <ul>
 *   <li>AC1: MEMBERS_ONLY 村の村史一覧を非メンバーが取得 → VILLAGE_081（403）</li>
 *   <li>AC2: 同村を村メンバーが取得 → 正常応答</li>
 *   <li>AC3: PUBLIC 村の村史一覧を非メンバーが取得 → 正常応答</li>
 *   <li>AC4: 単体取得（/chronicles/{yearMonth}）にも同じ認可が効く</li>
 *   <li>AC5: 村史 0 件でも空配列を返す（例外にしない）</li>
 * </ul>
 *
 * <h3>検証方針</h3>
 * <p>Controller を {@code @Autowired} し、Service〜Repository〜DB（Testcontainers MySQL）まで
 * 実 Bean を通す。日時フィクスチャは TZ 境界の 9 時間ズレを避けるため
 * {@link LocalDateTime} を bind して渡す（文字列リテラルを使わない）。</p>
 */
@DisplayName("VillageChronicleController 統合テスト（F17.1 Phase 3-β 村史の閲覧認可）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageChronicleControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageChronicleController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageChronicleRepository chronicleRepository;

    private static final Long HEADMAN_USER_ID = 9_810_001L;
    private static final Long VILLAGER_USER_ID = 9_810_002L;
    private static final Long NON_MEMBER_USER_ID = 9_810_003L;

    /** 対象月（月初正規化済み）。TZ 依存を避けるため固定値を用いる。 */
    private static final LocalDate TARGET_MONTH = LocalDate.of(2026, 5, 1);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ============================================================
    // AC1 / AC2 / AC3 — GET /chronicles 一覧の閲覧認可
    // ============================================================

    @Test
    @DisplayName("AC1: MEMBERS_ONLY 村の村史一覧を非メンバーが取得 → VILLAGE_081（403）")
    void list_membersOnly_nonMemberForbidden() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
        persistChronicle(village.getId(), TARGET_MONTH);
        authenticateAs(NON_MEMBER_USER_ID);

        assertThatThrownBy(() -> controller.list(village.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN));
    }

    @Test
    @DisplayName("AC2: MEMBERS_ONLY 村の村史一覧を村メンバーが取得 → 正常応答")
    void list_membersOnly_memberOk() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistChronicle(village.getId(), TARGET_MONTH);
        authenticateAs(VILLAGER_USER_ID);

        ApiResponse<List<ChronicleResponse>> res = controller.list(village.getId());

        assertThat(res.getData()).hasSize(1);
        assertThat(res.getData().get(0).yearMonth()).isEqualTo(TARGET_MONTH);
    }

    @Test
    @DisplayName("AC3: PUBLIC 村の村史一覧を非メンバーが取得 → 正常応答")
    void list_public_nonMemberOk() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.PUBLIC);
        persistChronicle(village.getId(), TARGET_MONTH);
        authenticateAs(NON_MEMBER_USER_ID);

        ApiResponse<List<ChronicleResponse>> res = controller.list(village.getId());

        assertThat(res.getData()).hasSize(1);
    }

    // ============================================================
    // AC4 — GET /chronicles/{yearMonth} 単体取得の閲覧認可
    // ============================================================

    @Test
    @DisplayName("AC4-1: MEMBERS_ONLY 村の村史単体取得を非メンバーが実行 → VILLAGE_081（403）")
    void get_membersOnly_nonMemberForbidden() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
        persistChronicle(village.getId(), TARGET_MONTH);
        authenticateAs(NON_MEMBER_USER_ID);

        assertThatThrownBy(() -> controller.get(village.getId(), TARGET_MONTH))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN));
    }

    @Test
    @DisplayName("AC4-2: 認可は村史の存在確認より先に効く（存在しない月でも非メンバーは 403）")
    void get_membersOnly_nonMemberForbiddenEvenIfChronicleAbsent() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
        authenticateAs(NON_MEMBER_USER_ID);

        // CHRONICLE_NOT_FOUND を返すと「その月の村史が無い」ことが非メンバーに漏れるため、
        // 認可エラーが優先されること（情報漏洩の防止）を確認する。
        assertThatThrownBy(() -> controller.get(village.getId(), TARGET_MONTH))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN));
    }

    @Test
    @DisplayName("AC4-3: MEMBERS_ONLY 村の村史単体取得を村メンバーが実行 → 正常応答")
    void get_membersOnly_memberOk() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
        persistMembership(village.getId(), HEADMAN_USER_ID, VillageRole.HEADMAN);
        persistChronicle(village.getId(), TARGET_MONTH);
        authenticateAs(HEADMAN_USER_ID);

        ApiResponse<ChronicleResponse> res = controller.get(village.getId(), TARGET_MONTH);

        assertThat(res.getData().yearMonth()).isEqualTo(TARGET_MONTH);
        assertThat(res.getData().topics()).extracting(ChronicleResponse.TopicItem::name)
                .containsExactly("練習試合");
    }

    @Test
    @DisplayName("AC4-4: PUBLIC 村の村史単体取得を非メンバーが実行 → 正常応答")
    void get_public_nonMemberOk() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.PUBLIC);
        persistChronicle(village.getId(), TARGET_MONTH);
        authenticateAs(NON_MEMBER_USER_ID);

        assertThatCode(() -> controller.get(village.getId(), TARGET_MONTH))
                .doesNotThrowAnyException();
    }

    // ============================================================
    // AC5 — 村史 0 件
    // ============================================================

    @Test
    @DisplayName("AC5: 村史 0 件でも空配列を返す（例外にしない）")
    void list_emptyReturnsEmptyList() {
        VillageEntity village = persistVillage(VillageBulletinVisibility.PUBLIC);
        authenticateAs(NON_MEMBER_USER_ID);

        ApiResponse<List<ChronicleResponse>> res = controller.list(village.getId());

        assertThat(res.getData()).isNotNull().isEmpty();
    }

    // ============================================================
    // 村の存在性（IDOR 対策で 404 に統一）
    // ============================================================

    @Test
    @DisplayName("存在しない村の村史一覧 → VILLAGE_001（404）")
    void list_villageNotFound() {
        authenticateAs(NON_MEMBER_USER_ID);

        assertThatThrownBy(() -> controller.list(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND));
    }

    // ============================================================
    // フィクスチャ
    // ============================================================

    private VillageEntity persistVillage(VillageBulletinVisibility bulletinVisibility) {
        VillageEntity e = VillageEntity.builder()
                .slug("ch-" + Long.toHexString(System.nanoTime()))
                .name("村史テスト" + System.nanoTime())
                .description("村史の閲覧認可テスト用")
                .type(VillageType.OFFICIAL)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(bulletinVisibility)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_USER_ID)
                .build();
        return villageRepository.saveAndFlush(e);
    }

    private VillageMembershipEntity persistMembership(UUID villageId, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    /**
     * 村史を 1 件永続化する。
     *
     * <p>日時は文字列リテラルではなく {@link LocalDateTime} を bind して渡す
     * （文字列だと JST/UTC の 9 時間ズレで月境界の集計が壊れる既知の地雷）。</p>
     */
    private VillageChronicleEntity persistChronicle(UUID villageId, LocalDate yearMonth) {
        VillageChronicleEntity e = VillageChronicleEntity.builder()
                .villageId(villageId)
                .yearMonth(yearMonth)
                .generatedAt(yearMonth.plusMonths(1).atStartOfDay())
                .postCount(12)
                .newMemberCount(3)
                .topic1Name("練習試合")
                .topic1Count(5)
                .topic2Count(0)
                .topic3Count(0)
                .build();
        return chronicleRepository.saveAndFlush(e);
    }
}
