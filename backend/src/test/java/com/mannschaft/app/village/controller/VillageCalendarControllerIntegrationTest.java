package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.CalendarEventCreateRequest;
import com.mannschaft.app.village.dto.CalendarEventListResponse;
import com.mannschaft.app.village.dto.CalendarEventResponse;
import com.mannschaft.app.village.dto.CalendarEventUpdateRequest;
import com.mannschaft.app.village.entity.VillageCalendarEventEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageCalendarEventRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * F17.1 Phase 2 U8 — 村歳時記カレンダー Controller 統合テスト。
 *
 * <p>対象エンドポイント:</p>
 * <ul>
 *   <li>POST   /api/v1/villages/{vid}/calendar-events</li>
 *   <li>GET    /api/v1/villages/{vid}/calendar-events?year=&month=</li>
 *   <li>GET    /api/v1/villages/{vid}/calendar-events/{eid}</li>
 *   <li>PATCH  /api/v1/villages/{vid}/calendar-events/{eid}</li>
 *   <li>DELETE /api/v1/villages/{vid}/calendar-events/{eid}</li>
 * </ul>
 *
 * <p>各 EP につき正常系 + 代表的な異常系（権限 / 404 / 整合性）を検証する。</p>
 */
@DisplayName("VillageCalendarController 統合テスト（F17.1 Phase 2 U8）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageCalendarControllerIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageCalendarController controller;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageCalendarEventRepository calendarRepository;

    private static final Long ADMIN_USER_ID = 9_710_001L;
    private static final Long HEADMAN_USER_ID = 9_710_002L;
    private static final Long REGULAR_USER_ID = 9_710_003L;

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
    // POST /api/v1/villages/{vid}/calendar-events
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("POST — HEADMAN は 201 で作成成功")
    void create_headman201() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);

        CalendarEventCreateRequest req = new CalendarEventCreateRequest(
                "桃の節句", "ひな祭りの行事", LocalDate.of(2026, 3, 3),
                null, Boolean.TRUE, "🎎", "#FFC0CB");

        ResponseEntity<ApiResponse<CalendarEventResponse>> res = controller.create(v.getId(), req);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        CalendarEventResponse body = res.getBody().getData();
        assertThat(body.title()).isEqualTo("桃の節句");
        assertThat(body.isAnnualRecurring()).isTrue();
        assertThat(body.iconEmoji()).isEqualTo("🎎");
        assertThat(calendarRepository.findById(body.id())).isPresent();
    }

    @Test
    @DisplayName("POST — 一般ユーザーは MODERATION_FORBIDDEN")
    void create_regularForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();

        CalendarEventCreateRequest req = new CalendarEventCreateRequest(
                "七夕", null, LocalDate.of(2026, 7, 7),
                null, Boolean.TRUE, "🎋", null);

        assertThatThrownBy(() -> controller.create(v.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/{vid}/calendar-events?year=&month=
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET — 月別一覧で当月の毎年繰返イベントが取得できる")
    void listByMonth_returnsRecurring() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        // 年無視で 7 月のイベント
        VillageCalendarEventEntity e1 = persistEvent(v.getId(), "七夕",
                LocalDate.of(2020, 7, 7), true);
        // 8 月のイベント（一致しない）
        persistEvent(v.getId(), "夏祭り", LocalDate.of(2020, 8, 15), true);

        ApiResponse<CalendarEventListResponse> res = controller.listByMonth(v.getId(), 2026, 7);

        CalendarEventListResponse body = res.getData();
        assertThat(body.year()).isEqualTo(2026);
        assertThat(body.month()).isEqualTo(7);
        assertThat(body.items()).extracting(CalendarEventResponse::id).contains(e1.getId());
        assertThat(body.items()).extracting(CalendarEventResponse::title).contains("七夕");
    }

    @Test
    @DisplayName("GET — 月引数が不正(13)なら VILLAGE_FIELD_INVALID")
    void listByMonth_invalidMonth() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();

        assertThatThrownBy(() -> controller.listByMonth(v.getId(), 2026, 13))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/villages/{vid}/calendar-events/{eid}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("GET 詳細 — 存在するイベントは 200")
    void get_ok() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        VillageCalendarEventEntity e = persistEvent(v.getId(), "年越し",
                LocalDate.of(2020, 12, 31), true);

        ApiResponse<CalendarEventResponse> res = controller.get(v.getId(), e.getId());

        assertThat(res.getData().id()).isEqualTo(e.getId());
        assertThat(res.getData().title()).isEqualTo("年越し");
    }

    @Test
    @DisplayName("GET 詳細 — 存在しないIDは CALENDAR_EVENT_NOT_FOUND")
    void get_notFound() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        UUID missing = UUID.fromString("01956cff-ffff-7000-8000-fffffffffffd");

        assertThatThrownBy(() -> controller.get(v.getId(), missing))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.CALENDAR_EVENT_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/villages/{vid}/calendar-events/{eid}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("PATCH — HEADMAN によるタイトル/色変更は 200")
    void update_headman200() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);
        VillageCalendarEventEntity e = persistEvent(v.getId(), "旧タイトル",
                LocalDate.of(2026, 5, 5), false);

        CalendarEventUpdateRequest req = new CalendarEventUpdateRequest(
                "新タイトル", null, null, null, null, null, "#00FF00");

        ApiResponse<CalendarEventResponse> res = controller.update(v.getId(), e.getId(), req);

        assertThat(res.getData().title()).isEqualTo("新タイトル");
        assertThat(res.getData().colorHex()).isEqualTo("#00FF00");
    }

    @Test
    @DisplayName("PATCH — 色が #RRGGBB 形式外なら CALENDAR_EVENT_INVALID_COLOR")
    void update_invalidColor() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);
        VillageCalendarEventEntity e = persistEvent(v.getId(), "色テスト",
                LocalDate.of(2026, 5, 5), false);

        CalendarEventUpdateRequest req = new CalendarEventUpdateRequest(
                null, null, null, null, null, null, "ZZZZZZZ");

        assertThatThrownBy(() -> controller.update(v.getId(), e.getId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.CALENDAR_EVENT_INVALID_COLOR);
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/villages/{vid}/calendar-events/{eid}
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("DELETE — HEADMAN による論理削除は 204")
    void delete_headman204() {
        authenticateAs(HEADMAN_USER_ID);
        VillageEntity v = persistVillage();
        persistHeadman(v.getId(), HEADMAN_USER_ID);
        VillageCalendarEventEntity e = persistEvent(v.getId(), "削除対象",
                LocalDate.of(2026, 6, 6), false);

        ResponseEntity<Void> res = controller.delete(v.getId(), e.getId());

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        VillageCalendarEventEntity reloaded = calendarRepository.findById(e.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("DELETE — 一般ユーザーは MODERATION_FORBIDDEN")
    void delete_regularForbidden() {
        authenticateAs(REGULAR_USER_ID);
        VillageEntity v = persistVillage();
        VillageCalendarEventEntity e = persistEvent(v.getId(), "防御対象",
                LocalDate.of(2026, 6, 6), false);

        assertThatThrownBy(() -> controller.delete(v.getId(), e.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("vc-" + Long.toHexString(System.nanoTime()))
                .name("カレンダー村" + System.nanoTime())
                .description("Calendar test village")
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

    private VillageCalendarEventEntity persistEvent(UUID villageId, String title,
                                                     LocalDate eventDate, boolean recurring) {
        VillageCalendarEventEntity e = VillageCalendarEventEntity.builder()
                .villageId(villageId)
                .title(title)
                .description(null)
                .eventDate(eventDate)
                .eventEndDate(null)
                .isAnnualRecurring(recurring)
                .iconEmoji(null)
                .colorHex(null)
                .createdByUserId(ADMIN_USER_ID)
                .build();
        return calendarRepository.saveAndFlush(e);
    }
}
