package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.MatchApplicationCreateRequest;
import com.mannschaft.app.village.dto.MatchApplicationResponse;
import com.mannschaft.app.village.dto.MatchApplicationReviewRequest;
import com.mannschaft.app.village.dto.MatchRecruitCreateRequest;
import com.mannschaft.app.village.dto.MatchRecruitListResponse;
import com.mannschaft.app.village.dto.MatchRecruitResponse;
import com.mannschaft.app.village.dto.MatchRecruitUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMatchRecruitEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMatchRecruitApplicationRepository;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F17.1 Phase 2 U9 — VillageMatchRecruitController 統合テスト。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>POST   /api/v1/villages/{villageId}/match-recruits                       — 作成</li>
 *   <li>GET    /api/v1/villages/{villageId}/match-recruits                       — 一覧</li>
 *   <li>GET    /api/v1/villages/{villageId}/match-recruits/{recruitId}           — 詳細</li>
 *   <li>PATCH  /api/v1/villages/{villageId}/match-recruits/{recruitId}           — 更新</li>
 *   <li>POST   .../{recruitId}/close / fulfill / cancel                          — 状態遷移</li>
 *   <li>POST   .../{recruitId}/applications                                      — 応募</li>
 *   <li>GET    .../{recruitId}/applications                                      — 応募一覧</li>
 *   <li>POST   .../{recruitId}/applications/{applicationId}/withdraw             — 取下げ</li>
 *   <li>POST   .../{recruitId}/applications/{applicationId}/review               — 承認/却下</li>
 * </ul>
 *
 * <h3>検証方針</h3>
 * <p>Controller を {@code @Autowired} し、Service〜Repository〜DB（Testcontainers MySQL）まで実 Bean を通す。
 * 各 EP につき正常系 1 件＋異常系 1 件以上をカバーする（指示書 U9 要件）。</p>
 */
@DisplayName("VillageMatchRecruitController 統合テスト（F17.1 Phase 2 U9）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageMatchRecruitControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageMatchRecruitController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageMatchRecruitRepository recruitRepository;

    @Autowired
    private VillageMatchRecruitApplicationRepository applicationRepository;

    private static final Long HEADMAN_USER_ID = 9_800_001L;
    private static final Long VILLAGER_USER_ID = 9_800_002L;
    private static final Long OTHER_VILLAGER_USER_ID = 9_800_003L;
    private static final Long NON_MEMBER_USER_ID = 9_800_004L;

    @BeforeEach
    void setUp() {
        // 本タスクは SYSTEM_ADMIN 判定経路を通らないが、基底クラスの @MockitoBean が必須なので空セットアップ。
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ============================================================
    // POST /match-recruits — 作成
    // ============================================================

    @Test
    @DisplayName("POST /match-recruits — 村人は 201 で作成成功")
    void create_villagerOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        authenticateAs(VILLAGER_USER_ID);

        MatchRecruitCreateRequest req = new MatchRecruitCreateRequest(
                VillageMatchRecruitCategory.PRACTICE_MATCH,
                "練習試合募集",
                "土曜午後の練習試合相手を募集します",
                LocalDate.now().plusDays(7),
                LocalTime.of(13, 0),
                LocalTime.of(15, 0),
                "市民体育館",
                15,
                "メッセージにて",
                LocalDateTime.now().plusDays(5),
                null);

        ResponseEntity<ApiResponse<MatchRecruitResponse>> res = controller.create(village.getId(), req);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        MatchRecruitResponse body = res.getBody().getData();
        assertThat(body.title()).isEqualTo("練習試合募集");
        assertThat(body.status()).isEqualTo(VillageMatchRecruitStatus.OPEN);
        assertThat(body.postedByUserId()).isEqualTo(VILLAGER_USER_ID);
        assertThat(recruitRepository.findById(body.id())).isPresent();
    }

    @Test
    @DisplayName("POST /match-recruits — 非村人は NOT_MEMBER")
    void create_nonMemberForbidden() {
        VillageEntity village = persistVillage();
        authenticateAs(NON_MEMBER_USER_ID);

        MatchRecruitCreateRequest req = newCreateRequest();

        assertThatThrownBy(() -> controller.create(village.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ============================================================
    // GET /match-recruits — 一覧
    // ============================================================

    @Test
    @DisplayName("GET /match-recruits — フィルタなしで一覧取得 200")
    void list_ok() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistRecruit(village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        ResponseEntity<ApiResponse<MatchRecruitListResponse>> res =
                controller.list(village.getId(), null, null, null, null, 0, 20);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        MatchRecruitListResponse body = res.getBody().getData();
        assertThat(body.items()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(body.page()).isEqualTo(0);
        assertThat(body.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("GET /match-recruits — 不正な category クエリは VILLAGE_FIELD_INVALID")
    void list_invalidCategory() {
        VillageEntity village = persistVillage();
        authenticateAs(VILLAGER_USER_ID);

        assertThatThrownBy(() -> controller.list(
                village.getId(), "INVALID_CATEGORY", null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);
    }

    @Test
    @DisplayName("GET /match-recruits — 不正な status クエリは VILLAGE_FIELD_INVALID")
    void list_invalidStatus() {
        VillageEntity village = persistVillage();
        authenticateAs(VILLAGER_USER_ID);

        assertThatThrownBy(() -> controller.list(
                village.getId(), null, "ZOMBIE", null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);
    }

    // ============================================================
    // GET /match-recruits/{id} — 詳細
    // ============================================================

    @Test
    @DisplayName("GET /match-recruits/{id} — 村人は 200")
    void get_villagerOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        ResponseEntity<ApiResponse<MatchRecruitResponse>> res =
                controller.get(village.getId(), recruit.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().id()).isEqualTo(recruit.getId());
    }

    @Test
    @DisplayName("GET /match-recruits/{id} — 非村人は NOT_MEMBER")
    void get_nonMemberForbidden() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(NON_MEMBER_USER_ID);

        assertThatThrownBy(() -> controller.get(village.getId(), recruit.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ============================================================
    // PATCH /match-recruits/{id} — 更新
    // ============================================================

    @Test
    @DisplayName("PATCH /match-recruits/{id} — 投稿者本人は 200")
    void update_authorOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        MatchRecruitUpdateRequest req = new MatchRecruitUpdateRequest(
                null, "更新後タイトル", "更新後説明", null, null, null, null, null, null, null, null);

        ResponseEntity<ApiResponse<MatchRecruitResponse>> res =
                controller.update(village.getId(), recruit.getId(), req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().title()).isEqualTo("更新後タイトル");
    }

    @Test
    @DisplayName("PATCH /match-recruits/{id} — 投稿者以外は COMMON_002（権限なし）")
    void update_nonAuthorForbidden() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);

        MatchRecruitUpdateRequest req = new MatchRecruitUpdateRequest(
                null, "勝手な更新", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.update(village.getId(), recruit.getId(), req))
                .isInstanceOf(BusinessException.class);
    }

    // ============================================================
    // POST /match-recruits/{id}/close — 締切
    // ============================================================

    @Test
    @DisplayName("POST /match-recruits/{id}/close — 投稿者本人は CLOSED に遷移")
    void close_authorOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        ResponseEntity<ApiResponse<MatchRecruitResponse>> res =
                controller.close(village.getId(), recruit.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().status()).isEqualTo(VillageMatchRecruitStatus.CLOSED);
    }

    @Test
    @DisplayName("POST /match-recruits/{id}/close — HEADMAN は他人投稿でも CLOSED に遷移")
    void close_headmanOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), HEADMAN_USER_ID, VillageRole.HEADMAN);
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(HEADMAN_USER_ID);

        ResponseEntity<ApiResponse<MatchRecruitResponse>> res =
                controller.close(village.getId(), recruit.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().status()).isEqualTo(VillageMatchRecruitStatus.CLOSED);
    }

    @Test
    @DisplayName("POST /match-recruits/{id}/close — 第三者村人は MODERATION_FORBIDDEN")
    void close_otherVillagerForbidden() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);

        assertThatThrownBy(() -> controller.close(village.getId(), recruit.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ============================================================
    // POST /match-recruits/{id}/fulfill — 成立確定
    // ============================================================

    @Test
    @DisplayName("POST /match-recruits/{id}/fulfill — 投稿者本人は FULFILLED に遷移")
    void fulfill_authorOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        ResponseEntity<ApiResponse<MatchRecruitResponse>> res =
                controller.fulfill(village.getId(), recruit.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().status()).isEqualTo(VillageMatchRecruitStatus.FULFILLED);
    }

    @Test
    @DisplayName("POST /match-recruits/{id}/fulfill — 既に CLOSED の募集は MATCH_RECRUIT_NOT_OPEN")
    void fulfill_alreadyClosed() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.CLOSED,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        assertThatThrownBy(() -> controller.fulfill(village.getId(), recruit.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MATCH_RECRUIT_NOT_OPEN);
    }

    // ============================================================
    // POST /match-recruits/{id}/cancel — 取消し
    // ============================================================

    @Test
    @DisplayName("POST /match-recruits/{id}/cancel — 投稿者本人は CANCELLED に遷移")
    void cancel_authorOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        ResponseEntity<ApiResponse<MatchRecruitResponse>> res =
                controller.cancel(village.getId(), recruit.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().status()).isEqualTo(VillageMatchRecruitStatus.CANCELLED);
    }

    @Test
    @DisplayName("POST /match-recruits/{id}/cancel — 第三者村人は MODERATION_FORBIDDEN")
    void cancel_otherVillagerForbidden() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);

        assertThatThrownBy(() -> controller.cancel(village.getId(), recruit.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ============================================================
    // POST /applications — 応募
    // ============================================================

    @Test
    @DisplayName("POST /applications — 村人は 201 で応募作成")
    void apply_villagerOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);

        MatchApplicationCreateRequest req = new MatchApplicationCreateRequest("ぜひお願いします", null);

        ResponseEntity<ApiResponse<MatchApplicationResponse>> res =
                controller.apply(village.getId(), recruit.getId(), req);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        MatchApplicationResponse body = res.getBody().getData();
        assertThat(body.applicantUserId()).isEqualTo(OTHER_VILLAGER_USER_ID);
        assertThat(body.status()).isEqualTo(VillageMatchApplicationStatus.PENDING);
    }

    @Test
    @DisplayName("POST /applications — 投稿者本人の自己応募は COMMON_002")
    void apply_selfApplyForbidden() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(VILLAGER_USER_ID);

        MatchApplicationCreateRequest req = new MatchApplicationCreateRequest("自分の募集に応募", null);

        assertThatThrownBy(() -> controller.apply(village.getId(), recruit.getId(), req))
                .isInstanceOf(BusinessException.class);
    }

    // ============================================================
    // GET /applications — 応募一覧
    // ============================================================

    @Test
    @DisplayName("GET /applications — 投稿者本人は応募一覧取得 200")
    void listApplications_authorOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        // 応募を 1 件挿入
        authenticateAs(OTHER_VILLAGER_USER_ID);
        controller.apply(village.getId(), recruit.getId(),
                new MatchApplicationCreateRequest("お願いします", null));

        authenticateAs(VILLAGER_USER_ID);
        ResponseEntity<ApiResponse<List<MatchApplicationResponse>>> res =
                controller.listApplications(village.getId(), recruit.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData()).hasSize(1);
        assertThat(res.getBody().getData().get(0).applicantUserId()).isEqualTo(OTHER_VILLAGER_USER_ID);
    }

    @Test
    @DisplayName("GET /applications — 第三者村人は MODERATION_FORBIDDEN")
    void listApplications_otherVillagerForbidden() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);

        assertThatThrownBy(() -> controller.listApplications(village.getId(), recruit.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ============================================================
    // POST /applications/{id}/withdraw — 取下げ
    // ============================================================

    @Test
    @DisplayName("POST /applications/{id}/withdraw — 応募者本人は WITHDRAWN に遷移")
    void withdraw_applicantOk() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);
        MatchApplicationResponse app = controller.apply(village.getId(), recruit.getId(),
                new MatchApplicationCreateRequest("お願いします", null)).getBody().getData();

        ResponseEntity<ApiResponse<MatchApplicationResponse>> res =
                controller.withdraw(village.getId(), recruit.getId(), app.id());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().status()).isEqualTo(VillageMatchApplicationStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("POST /applications/{id}/withdraw — 応募者以外は COMMON_002")
    void withdraw_otherUserForbidden() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);
        MatchApplicationResponse app = controller.apply(village.getId(), recruit.getId(),
                new MatchApplicationCreateRequest("お願いします", null)).getBody().getData();

        // 投稿者（=応募者ではない）が取下げを試みる
        authenticateAs(VILLAGER_USER_ID);

        assertThatThrownBy(() -> controller.withdraw(village.getId(), recruit.getId(), app.id()))
                .isInstanceOf(BusinessException.class);
    }

    // ============================================================
    // POST /applications/{id}/review — 承認/却下
    // ============================================================

    @Test
    @DisplayName("POST /applications/{id}/review — 投稿者本人が ACCEPTED に審査")
    void review_accepted() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);
        MatchApplicationResponse app = controller.apply(village.getId(), recruit.getId(),
                new MatchApplicationCreateRequest("お願いします", null)).getBody().getData();

        authenticateAs(VILLAGER_USER_ID);
        MatchApplicationReviewRequest req = new MatchApplicationReviewRequest(
                VillageMatchApplicationStatus.ACCEPTED, "了解です");

        ResponseEntity<ApiResponse<MatchApplicationResponse>> res =
                controller.review(village.getId(), recruit.getId(), app.id(), req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().getData().status()).isEqualTo(VillageMatchApplicationStatus.ACCEPTED);
        assertThat(res.getBody().getData().reviewedByUserId()).isEqualTo(VILLAGER_USER_ID);
    }

    @Test
    @DisplayName("POST /applications/{id}/review — status=PENDING の審査リクエストは MATCH_APPLICATION_INVALID_STATUS")
    void review_invalidTargetStatus() {
        VillageEntity village = persistVillage();
        persistMembership(village.getId(), VILLAGER_USER_ID, VillageRole.VILLAGER);
        persistMembership(village.getId(), OTHER_VILLAGER_USER_ID, VillageRole.VILLAGER);
        VillageMatchRecruitEntity recruit = persistRecruit(
                village.getId(), VILLAGER_USER_ID, VillageMatchRecruitStatus.OPEN,
                VillageMatchRecruitCategory.PRACTICE_MATCH, LocalDate.now().plusDays(7));
        authenticateAs(OTHER_VILLAGER_USER_ID);
        MatchApplicationResponse app = controller.apply(village.getId(), recruit.getId(),
                new MatchApplicationCreateRequest("お願いします", null)).getBody().getData();

        authenticateAs(VILLAGER_USER_ID);
        // ACCEPTED/REJECTED 以外は不正
        MatchApplicationReviewRequest req = new MatchApplicationReviewRequest(
                VillageMatchApplicationStatus.PENDING, null);

        assertThatThrownBy(() -> controller.review(village.getId(), recruit.getId(), app.id(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MATCH_APPLICATION_INVALID_STATUS);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private VillageEntity persistVillage() {
        VillageEntity e = VillageEntity.builder()
                .slug("mr-" + Long.toHexString(System.nanoTime()))
                .name("試合村テスト" + System.nanoTime())
                .description("練習試合募集テスト用")
                .type(VillageType.OFFICIAL)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
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

    private VillageMatchRecruitEntity persistRecruit(UUID villageId,
                                                     Long postedByUserId,
                                                     VillageMatchRecruitStatus status,
                                                     VillageMatchRecruitCategory category,
                                                     LocalDate matchDate) {
        VillageMatchRecruitEntity e = VillageMatchRecruitEntity.builder()
                .villageId(villageId)
                .postedByUserId(postedByUserId)
                .category(category)
                .title("テスト募集")
                .description("テスト説明")
                .matchDate(matchDate)
                .matchTimeStart(LocalTime.of(13, 0))
                .matchTimeEnd(LocalTime.of(15, 0))
                .venue("テスト会場")
                .requiredCount(15)
                .contactMethod("メッセージにて")
                .applicationDeadline(LocalDateTime.now().plusDays(3))
                .status(status)
                .build();
        return recruitRepository.saveAndFlush(e);
    }

    private MatchRecruitCreateRequest newCreateRequest() {
        return new MatchRecruitCreateRequest(
                VillageMatchRecruitCategory.PRACTICE_MATCH,
                "テスト募集",
                "テスト説明",
                LocalDate.now().plusDays(7),
                LocalTime.of(13, 0),
                LocalTime.of(15, 0),
                "テスト会場",
                15,
                "メッセージにて",
                LocalDateTime.now().plusDays(3),
                null);
    }
}
