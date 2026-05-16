package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.FestivalCreateRequest;
import com.mannschaft.app.village.dto.FestivalResponse;
import com.mannschaft.app.village.dto.FestivalUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * F17.1 Phase 2 U8 — 村お祭り Controller 統合テスト。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>POST   /api/v1/villages/{vid}/festivals</li>
 *   <li>GET    /api/v1/villages/{vid}/festivals?status=</li>
 *   <li>GET    /api/v1/villages/{vid}/festivals/{fid}</li>
 *   <li>PATCH  /api/v1/villages/{vid}/festivals/{fid}</li>
 *   <li>POST   /api/v1/villages/{vid}/festivals/{fid}/cancel</li>
 * </ul>
 */
@DisplayName("VillageFestivalController 統合テスト（F17.1 Phase 2 U8）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageFestivalControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageFestivalController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageFestivalRepository festivalRepository;

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
    // POST /api/v1/villages/{vid}/festivals
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST — HEADMAN は 201 で SCHEDULED として作成成功")
    void create_headman201Scheduled() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);

        LocalDateTime starts = LocalDateTime.now().plusDays(7);
        LocalDateTime ends = starts.plusDays(2);
        FestivalCreateRequest req = new FestivalCreateRequest(
                "夏祭り", "夜店と花火", starts, ends, null, "#FF8800");

        ResponseEntity<ApiResponse<FestivalResponse>> res = controller.create(v.getId(), req);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        FestivalResponse body = res.getBody().getData();
        assertThat(body.title()).isEqualTo("夏祭り");
        assertThat(body.status()).isEqualTo(VillageFestivalStatus.SCHEDULED);
        assertThat(festivalRepository.findById(body.id())).isPresent();
    }

    @Test
    @DisplayName("POST — 終了時刻 <= 開始時刻なら FESTIVAL_INVALID_PERIOD")
    void create_invalidPeriod() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);

        LocalDateTime starts = LocalDateTime.now().plusDays(7);
        FestivalCreateRequest req = new FestivalCreateRequest(
                "壊れた祭り", null, starts, starts, null, null);

        assertThatThrownBy(() -> controller.create(v.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.FESTIVAL_INVALID_PERIOD);
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/{vid}/festivals
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET — 一覧取得は status フィルタなしで全件返す")
    void list_allStatuses() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        VillageFestivalEntity sched = persistFestival(v.getId(), "予定祭", VillageFestivalStatus.SCHEDULED);
        VillageFestivalEntity ended = persistFestival(v.getId(), "終了祭", VillageFestivalStatus.ENDED);

        ApiResponse<List<FestivalResponse>> res = controller.list(v.getId(), null, 0, 20);

        assertThat(res.getData())
                .extracting(FestivalResponse::id)
                .contains(sched.getId(), ended.getId());
    }

    @Test
    @DisplayName("GET — status=ACTIVE で絞り込み一覧")
    void list_filterByActive() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        VillageFestivalEntity active = persistFestival(v.getId(), "開催中", VillageFestivalStatus.ACTIVE);
        persistFestival(v.getId(), "終了祭", VillageFestivalStatus.ENDED);

        ApiResponse<List<FestivalResponse>> res =
                controller.list(v.getId(), VillageFestivalStatus.ACTIVE, 0, 20);

        assertThat(res.getData())
                .extracting(FestivalResponse::id)
                .containsExactly(active.getId());
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/{vid}/festivals/{fid}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET 詳細 — 存在する祭りは 200")
    void get_ok() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        VillageFestivalEntity f = persistFestival(v.getId(), "詳細対象", VillageFestivalStatus.SCHEDULED);

        ApiResponse<FestivalResponse> res = controller.get(v.getId(), f.getId());

        assertThat(res.getData().id()).isEqualTo(f.getId());
        assertThat(res.getData().title()).isEqualTo("詳細対象");
    }

    @Test
    @DisplayName("GET 詳細 — 別村のIDを指定すると FESTIVAL_NOT_FOUND")
    void get_idor() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v1 = persistVillage();
        VillageEntity v2 = persistVillage();
        VillageFestivalEntity f = persistFestival(v2.getId(), "別村祭",
                VillageFestivalStatus.SCHEDULED);

        assertThatThrownBy(() -> controller.get(v1.getId(), f.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.FESTIVAL_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/villages/{vid}/festivals/{fid}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH — HEADMAN によるタイトル変更は 200")
    void update_headman200() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);
        VillageFestivalEntity f = persistFestival(v.getId(), "旧タイトル",
                VillageFestivalStatus.SCHEDULED);

        FestivalUpdateRequest req = new FestivalUpdateRequest(
                "新タイトル", null, null, null, null, null);

        ApiResponse<FestivalResponse> res = controller.update(v.getId(), f.getId(), req);

        assertThat(res.getData().title()).isEqualTo("新タイトル");
    }

    @Test
    @DisplayName("PATCH — ENDED 祭りの更新は FESTIVAL_ALREADY_ENDED")
    void update_endedFestival() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);
        VillageFestivalEntity f = persistFestival(v.getId(), "終了済", VillageFestivalStatus.ENDED);

        FestivalUpdateRequest req = new FestivalUpdateRequest(
                "更新試行", null, null, null, null, null);

        assertThatThrownBy(() -> controller.update(v.getId(), f.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.FESTIVAL_ALREADY_ENDED);
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/villages/{vid}/festivals/{fid}/cancel
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST cancel — HEADMAN による中止は CANCELLED に遷移")
    void cancel_headmanOk() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);
        VillageFestivalEntity f = persistFestival(v.getId(), "中止対象",
                VillageFestivalStatus.SCHEDULED);

        ApiResponse<FestivalResponse> res = controller.cancel(v.getId(), f.getId());

        assertThat(res.getData().status()).isEqualTo(VillageFestivalStatus.CANCELLED);
        VillageFestivalEntity reloaded = festivalRepository.findById(f.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(VillageFestivalStatus.CANCELLED);
    }

    @Test
    @DisplayName("POST cancel — 一般ユーザーは MODERATION_FORBIDDEN")
    void cancel_regularForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        VillageFestivalEntity f = persistFestival(v.getId(), "防御対象",
                VillageFestivalStatus.SCHEDULED);

        assertThatThrownBy(() -> controller.cancel(v.getId(), f.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("vf-" + Long.toHexString(System.nanoTime()))
                .name("祭り村" + System.nanoTime())
                .description("Festival test village")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(ADMIN_USER_ID)
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

    private VillageFestivalEntity persistFestival(UUID villageId, String title,
                                                   VillageFestivalStatus status) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime starts;
        LocalDateTime ends;
        switch (status) {
            case SCHEDULED -> {
                starts = now.plusDays(7);
                ends = starts.plusDays(2);
            }
            case ACTIVE -> {
                starts = now.minusHours(1);
                ends = now.plusDays(1);
            }
            case ENDED -> {
                starts = now.minusDays(7);
                ends = now.minusDays(5);
            }
            case CANCELLED -> {
                starts = now.plusDays(1);
                ends = now.plusDays(2);
            }
            default -> {
                starts = now;
                ends = now.plusDays(1);
            }
        }
        VillageFestivalEntity f = VillageFestivalEntity.builder()
                .villageId(villageId)
                .title(title)
                .description(null)
                .startsAt(starts)
                .endsAt(ends)
                .bannerR2Key(null)
                .themeColorHex(null)
                .status(status)
                .createdByUserId(ADMIN_USER_ID)
                .build();
        return festivalRepository.saveAndFlush(f);
    }
}
