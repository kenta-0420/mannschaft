package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.RepresentativeGrantRequest;
import com.mannschaft.app.village.dto.RepresentativeResponse;
import com.mannschaft.app.village.dto.RepresentativeRevokeRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageRepresentativeEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageRepresentativeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * F17 Phase 2 U7 — VillageRepresentativeController 統合テスト。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>POST   /api/v1/villages/{villageId}/representatives                       — 代表委任の付与</li>
 *   <li>DELETE /api/v1/villages/{villageId}/representatives/{representativeId}    — 代表委任の取消し</li>
 *   <li>GET    /api/v1/villages/{villageId}/representatives                       — 代表委任一覧</li>
 * </ul>
 *
 * <p>{@link UserRoleRepository} は MockitoBean で差し替え、チーム/組織メンバー判定のみ
 * テスト側で制御する。Village ドメイン側の実 Bean は通す。</p>
 */
@DisplayName("VillageRepresentativeController 統合テスト（F17 Phase 2 U7）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageRepresentativeControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageRepresentativeController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageRepresentativeRepository representativeRepository;

    /** チーム/組織メンバー検証は外部ドメイン依存ゆえモック化（テストコスト圧縮）。 */
    @MockitoBean
    private UserRoleRepository userRoleRepository;

    private static final Long HEADMAN_USER_ID = 9_710_001L;
    private static final Long REGULAR_USER_ID = 9_710_002L;
    private static final Long REPRESENTATIVE_USER_ID = 9_710_003L;
    private static final Long TEAM_ID = 9_710_500L;

    @BeforeEach
    void setUp() {
        // デフォルト: チーム所属判定は false（拒否側）
        lenient().when(userRoleRepository.existsByUserIdAndTeamId(anyLong(), anyLong())).thenReturn(false);
        lenient().when(userRoleRepository.existsByUserIdAndOrganizationId(anyLong(), anyLong())).thenReturn(false);
        // 代表ユーザーはチームメンバー扱い
        lenient().when(userRoleRepository.existsByUserIdAndTeamId(REPRESENTATIVE_USER_ID, TEAM_ID)).thenReturn(true);
        // CMP-050: 代表ユーザーのアカウントは生存（未削除かつ ACTIVE）している前提。
        // isActiveUser は default メソッドだが Mockito は default 実装を呼ばず既定 false を返すため、
        // 明示的に stub しないと委任がすべて VILLAGE_055 で拒否される。
        lenient().when(userRoleRepository.isActiveUser(REPRESENTATIVE_USER_ID)).thenReturn(true);
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
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
    // POST /api/v1/villages/{villageId}/representatives
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST — HEADMAN が TEAM メンバーシップへ代表委任を付与 → 201")
    void grant_headman_created() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity village = persistVillage();
        persistHeadman(village.getId(), HEADMAN_USER_ID);
        VillageMembershipEntity teamMembership = persistTeamMembership(village.getId(), TEAM_ID);

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                teamMembership.getId(), REPRESENTATIVE_USER_ID, "U7 統合テスト");

        ResponseEntity<ApiResponse<RepresentativeResponse>> res = controller.grant(village.getId(), req);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        RepresentativeResponse body = res.getBody().getData();
        assertThat(body.villageId()).isEqualTo(village.getId());
        assertThat(body.membershipId()).isEqualTo(teamMembership.getId());
        assertThat(body.representativeUserId()).isEqualTo(REPRESENTATIVE_USER_ID);
        assertThat(body.revokedAt()).isNull();
        assertThat(representativeRepository.existsByMembershipIdAndRepresentativeUserIdAndRevokedAtIsNull(
                teamMembership.getId(), REPRESENTATIVE_USER_ID)).isTrue();
    }

    @Test
    @DisplayName("POST — 一般ユーザーが委任しようとすると VILLAGE_024 MODERATION_FORBIDDEN")
    void grant_regularUser_forbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity village = persistVillage();
        VillageMembershipEntity teamMembership = persistTeamMembership(village.getId(), TEAM_ID);

        RepresentativeGrantRequest req = new RepresentativeGrantRequest(
                teamMembership.getId(), REPRESENTATIVE_USER_ID, null);

        assertThatThrownBy(() -> controller.grant(village.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/villages/{villageId}/representatives/{representativeId}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE — HEADMAN が現役委任を取消し → 200 + revokedAt がセット")
    void revoke_headman_ok() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity village = persistVillage();
        persistHeadman(village.getId(), HEADMAN_USER_ID);
        VillageMembershipEntity teamMembership = persistTeamMembership(village.getId(), TEAM_ID);
        VillageRepresentativeEntity rep = persistActiveRepresentative(
                village.getId(), teamMembership.getId(), REPRESENTATIVE_USER_ID, HEADMAN_USER_ID);

        ResponseEntity<ApiResponse<RepresentativeResponse>> res = controller.revoke(
                village.getId(), rep.getId(),
                new RepresentativeRevokeRequest("U7 取消し統合テスト"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        RepresentativeResponse body = res.getBody().getData();
        assertThat(body.revokedAt()).isNotNull();
        VillageRepresentativeEntity reloaded = representativeRepository.findById(rep.getId()).orElseThrow();
        assertThat(reloaded.getRevokedAt()).isNotNull();
        assertThat(reloaded.getRevokedByUserId()).isEqualTo(HEADMAN_USER_ID);
    }

    @Test
    @DisplayName("DELETE — 存在しない representativeId は VILLAGE_052 REPRESENTATIVE_NOT_FOUND")
    void revoke_notFound() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity village = persistVillage();
        persistHeadman(village.getId(), HEADMAN_USER_ID);
        UUID missing = UUID.fromString("01956cff-eeee-7000-8000-eeeeeeeeeeee");

        assertThatThrownBy(() -> controller.revoke(village.getId(), missing, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.REPRESENTATIVE_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/{villageId}/representatives
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET — 現役委任が一覧で返る（村人・非管理者メンバーでも閲覧可）")
    void list_active_only() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity village = persistVillage();
        persistVillager(village.getId(), REGULAR_USER_ID);
        VillageMembershipEntity teamMembership = persistTeamMembership(village.getId(), TEAM_ID);
        VillageRepresentativeEntity rep = persistActiveRepresentative(
                village.getId(), teamMembership.getId(), REPRESENTATIVE_USER_ID, HEADMAN_USER_ID);

        ResponseEntity<ApiResponse<List<RepresentativeResponse>>> res = controller.list(village.getId(), false);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<RepresentativeResponse> body = res.getBody().getData();
        assertThat(body).extracting(RepresentativeResponse::id).contains(rep.getId());
    }

    @Test
    @DisplayName("GET — includeRevoked=false なら取消し済みは返らない（村人）")
    void list_excludes_revoked() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity village = persistVillage();
        persistVillager(village.getId(), REGULAR_USER_ID);
        VillageMembershipEntity teamMembership = persistTeamMembership(village.getId(), TEAM_ID);
        VillageRepresentativeEntity rep = persistActiveRepresentative(
                village.getId(), teamMembership.getId(), REPRESENTATIVE_USER_ID, HEADMAN_USER_ID);
        // 取消し済みにしておく
        rep.setRevokedAt(LocalDateTime.now());
        rep.setRevokedByUserId(HEADMAN_USER_ID);
        representativeRepository.saveAndFlush(rep);

        ResponseEntity<ApiResponse<List<RepresentativeResponse>>> res = controller.list(village.getId(), false);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<RepresentativeResponse> body = res.getBody().getData();
        assertThat(body).extracting(RepresentativeResponse::id).doesNotContain(rep.getId());
    }

    @Test
    @DisplayName("GET — HEADMAN 本人も一覧を閲覧できる（正当な権限保持者・200）")
    void list_headman_ok() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity village = persistVillage();
        persistHeadman(village.getId(), HEADMAN_USER_ID);
        VillageMembershipEntity teamMembership = persistTeamMembership(village.getId(), TEAM_ID);
        VillageRepresentativeEntity rep = persistActiveRepresentative(
                village.getId(), teamMembership.getId(), REPRESENTATIVE_USER_ID, HEADMAN_USER_ID);

        ResponseEntity<ApiResponse<List<RepresentativeResponse>>> res = controller.list(village.getId(), false);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData()).extracting(RepresentativeResponse::id).contains(rep.getId());
    }

    @Test
    @DisplayName("GET — 非村人は VILLAGE_007（NOT_MEMBER）で拒否")
    void list_nonMember_forbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity village = persistVillage();
        // REGULAR_USER_ID は当該村のメンバーシップを持たない

        assertThatThrownBy(() -> controller.list(village.getId(), false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    @Test
    @DisplayName("GET — 別村の HEADMAN であっても当該村の非会員なら VILLAGE_007（BOLA 対策）")
    void list_headmanOfOtherVillage_forbidden() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity otherVillage = persistVillage();
        persistHeadman(otherVillage.getId(), HEADMAN_USER_ID);
        VillageEntity targetVillage = persistVillage();
        // HEADMAN_USER_ID は targetVillage には所属していない

        assertThatThrownBy(() -> controller.list(targetVillage.getId(), false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private VillageEntity persistVillage() {
        VillageEntity e = VillageEntity.builder()
                .slug("vt-rep-" + Long.toHexString(System.nanoTime()))
                .name("代表テスト村" + System.nanoTime())
                .description("代表委任テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_USER_ID)
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

    private VillageMembershipEntity persistTeamMembership(UUID villageId, Long teamId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.TEAM)
                .subjectId(teamId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    private VillageRepresentativeEntity persistActiveRepresentative(UUID villageId,
                                                                    UUID membershipId,
                                                                    Long representativeUserId,
                                                                    Long grantedByUserId) {
        VillageRepresentativeEntity e = VillageRepresentativeEntity.builder()
                .villageId(villageId)
                .membershipId(membershipId)
                .representativeUserId(representativeUserId)
                .grantedByUserId(grantedByUserId)
                .grantedAt(LocalDateTime.now())
                .note("seed")
                .build();
        return representativeRepository.saveAndFlush(e);
    }
}
