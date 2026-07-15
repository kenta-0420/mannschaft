package com.mannschaft.app.village.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageCalendarService} 単体テスト（F17.1 Phase 2 U4）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>作成成功（HEADMAN / ELDER）</li>
 *   <li>作成: 一般村人は VILLAGE_024（MODERATION_FORBIDDEN）で拒否</li>
 *   <li>作成: 非村人は VILLAGE_024 で拒否</li>
 *   <li>作成: event_end_date &lt; event_date で VILLAGE_057</li>
 *   <li>作成: 色形式不正で VILLAGE_058（#XYZ などの不正）</li>
 *   <li>更新: 部分更新で title だけ書き換え</li>
 *   <li>更新: 別村のイベントID指定で VILLAGE_056（IDOR 防止）</li>
 *   <li>削除: 論理削除（deletedAt セット）</li>
 *   <li>削除: 既に削除済みは VILLAGE_056</li>
 *   <li>月別取得: 毎年繰返・単発の両方を含む並び</li>
 *   <li>月別取得: month 範囲外は VILLAGE_FIELD_INVALID</li>
 *   <li>詳細取得: 削除済みは VILLAGE_056</li>
 *   <li>削除済み村への作成は VILLAGE_001</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageCalendarService 単体テスト")
class VillageCalendarServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final UUID OTHER_VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000ff");
    private static final UUID EVENT_ID = UUID.fromString("01956c00-0000-7000-8000-000000000aaa");
    private static final UUID HEADMAN_MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b01");
    private static final UUID ELDER_MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b02");
    private static final UUID VILLAGER_MEMBERSHIP_ID = UUID.fromString("01956c00-0000-7000-8000-000000000b03");
    private static final Long HEADMAN_USER_ID = 200L;
    private static final Long ELDER_USER_ID = 201L;
    private static final Long VILLAGER_USER_ID = 202L;
    private static final Long NON_MEMBER_USER_ID = 999L;

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageCalendarEventRepository calendarRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;

    @InjectMocks
    private VillageCalendarService service;

    private VillageEntity village;

    @BeforeEach
    void setUp() {
        village = VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        village.setId(VILLAGE_ID);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private VillageMembershipEntity memberOf(UUID id, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        m.setId(id);
        return m;
    }

    private VillageCalendarEventEntity existingEvent(boolean annual) {
        VillageCalendarEventEntity e = VillageCalendarEventEntity.builder()
                .villageId(VILLAGE_ID)
                .title("七夕")
                .description("短冊に願いを書こう")
                .eventDate(LocalDate.of(2026, 7, 7))
                .isAnnualRecurring(annual)
                .iconEmoji("🎋")
                .colorHex("#33AA77")
                .createdByUserId(HEADMAN_USER_ID)
                .createdAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .build();
        e.setId(EVENT_ID);
        return e;
    }

    private CalendarEventCreateRequest validCreateRequest() {
        return new CalendarEventCreateRequest(
                "七夕",
                "短冊に願いを書こう",
                LocalDate.of(2026, 7, 7),
                null,
                Boolean.TRUE,
                "🎋",
                "#33AA77");
    }

    private void mockModerator(Long userId, UUID membershipId, VillageRole role) {
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, userId))
                .willReturn(Optional.of(memberOf(membershipId, userId, role)));
    }

    // ========================================================================
    // 作成
    // ========================================================================

    @Test
    @DisplayName("作成: HEADMAN は歳時記イベントを作成できる")
    void createEvent_byHeadman_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(HEADMAN_USER_ID, HEADMAN_MEMBERSHIP_ID, VillageRole.HEADMAN);
        given(calendarRepository.save(any(VillageCalendarEventEntity.class)))
                .willAnswer(inv -> {
                    VillageCalendarEventEntity e = inv.getArgument(0);
                    e.setId(EVENT_ID);
                    e.setCreatedAt(LocalDateTime.now());
                    return e;
                });

        CalendarEventResponse res = service.createEvent(VILLAGE_ID, validCreateRequest(), HEADMAN_USER_ID);

        assertThat(res.id()).isEqualTo(EVENT_ID);
        assertThat(res.title()).isEqualTo("七夕");
        assertThat(res.isAnnualRecurring()).isTrue();
        assertThat(res.colorHex()).isEqualTo("#33AA77");
        assertThat(res.createdByUserId()).isEqualTo(HEADMAN_USER_ID);
        assertThat(res.createdByDisplayName()).isNull();
    }

    @Test
    @DisplayName("作成: ELDER も歳時記イベントを作成できる")
    void createEvent_byElder_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(ELDER_USER_ID, ELDER_MEMBERSHIP_ID, VillageRole.ELDER);
        given(calendarRepository.save(any(VillageCalendarEventEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse res = service.createEvent(VILLAGE_ID, validCreateRequest(), ELDER_USER_ID);

        assertThat(res.title()).isEqualTo("七夕");
        verify(calendarRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("作成: 一般村人 VILLAGER は VILLAGE_024（MODERATION_FORBIDDEN）で拒否")
    void createEvent_byVillager_denied() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(VILLAGER_USER_ID, VILLAGER_MEMBERSHIP_ID, VillageRole.VILLAGER);

        assertThatThrownBy(() -> service.createEvent(VILLAGE_ID, validCreateRequest(), VILLAGER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
        verify(calendarRepository, never()).save(any());
    }

    @Test
    @DisplayName("作成: 非村人は VILLAGE_024 で拒否")
    void createEvent_byNonMember_denied() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, NON_MEMBER_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEvent(VILLAGE_ID, validCreateRequest(), NON_MEMBER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("作成: 期間逆転（end < start）は VILLAGE_057")
    void createEvent_dateRangeReversed() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(HEADMAN_USER_ID, HEADMAN_MEMBERSHIP_ID, VillageRole.HEADMAN);

        CalendarEventCreateRequest bad = new CalendarEventCreateRequest(
                "誤入力",
                null,
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 7), // < start
                Boolean.FALSE,
                null,
                null);

        assertThatThrownBy(() -> service.createEvent(VILLAGE_ID, bad, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.CALENDAR_EVENT_INVALID_DATE_RANGE);
        verify(calendarRepository, never()).save(any());
    }

    @Test
    @DisplayName("作成: カラーコード形式不正は VILLAGE_058")
    void createEvent_invalidColor() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(HEADMAN_USER_ID, HEADMAN_MEMBERSHIP_ID, VillageRole.HEADMAN);

        CalendarEventCreateRequest bad = new CalendarEventCreateRequest(
                "色不正",
                null,
                LocalDate.of(2026, 3, 3),
                null,
                Boolean.TRUE,
                null,
                "#XYZ"); // 形式不正

        assertThatThrownBy(() -> service.createEvent(VILLAGE_ID, bad, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.CALENDAR_EVENT_INVALID_COLOR);
    }

    @Test
    @DisplayName("作成: 削除済み村への作成は VILLAGE_001（NOT_FOUND）")
    void createEvent_onDeletedVillage_notFound() {
        village.setDeletedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));

        assertThatThrownBy(() -> service.createEvent(VILLAGE_ID, validCreateRequest(), HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    // ========================================================================
    // 更新
    // ========================================================================

    @Test
    @DisplayName("更新: title のみの部分更新が可能、他フィールドは保持")
    void updateEvent_partial_titleOnly() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(HEADMAN_USER_ID, HEADMAN_MEMBERSHIP_ID, VillageRole.HEADMAN);
        given(calendarRepository.findById(EVENT_ID)).willReturn(Optional.of(existingEvent(true)));
        given(calendarRepository.save(any(VillageCalendarEventEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        CalendarEventUpdateRequest req = new CalendarEventUpdateRequest(
                "七夕まつり",
                null, null, null, null, null, null);

        CalendarEventResponse res = service.updateEvent(VILLAGE_ID, EVENT_ID, req, HEADMAN_USER_ID);

        assertThat(res.title()).isEqualTo("七夕まつり");
        assertThat(res.description()).isEqualTo("短冊に願いを書こう");
        assertThat(res.colorHex()).isEqualTo("#33AA77");
        assertThat(res.isAnnualRecurring()).isTrue();
    }

    @Test
    @DisplayName("更新: 別村のイベントIDを指定すると VILLAGE_056（IDOR 対策で 404）")
    void updateEvent_crossVillage_idor() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(HEADMAN_USER_ID, HEADMAN_MEMBERSHIP_ID, VillageRole.HEADMAN);
        VillageCalendarEventEntity otherEvent = existingEvent(true);
        otherEvent.setVillageId(OTHER_VILLAGE_ID);
        given(calendarRepository.findById(EVENT_ID)).willReturn(Optional.of(otherEvent));

        CalendarEventUpdateRequest req = new CalendarEventUpdateRequest(
                "改ざん", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateEvent(VILLAGE_ID, EVENT_ID, req, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.CALENDAR_EVENT_NOT_FOUND);
    }

    // ========================================================================
    // 削除
    // ========================================================================

    @Test
    @DisplayName("削除: deletedAt がセットされる（論理削除）")
    void deleteEvent_setsDeletedAt() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(HEADMAN_USER_ID, HEADMAN_MEMBERSHIP_ID, VillageRole.HEADMAN);
        given(calendarRepository.findById(EVENT_ID)).willReturn(Optional.of(existingEvent(true)));

        service.deleteEvent(VILLAGE_ID, EVENT_ID, HEADMAN_USER_ID);

        ArgumentCaptor<VillageCalendarEventEntity> captor =
                ArgumentCaptor.forClass(VillageCalendarEventEntity.class);
        verify(calendarRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("削除: 既に削除済みイベントは VILLAGE_056")
    void deleteEvent_alreadyDeleted() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        mockModerator(HEADMAN_USER_ID, HEADMAN_MEMBERSHIP_ID, VillageRole.HEADMAN);
        VillageCalendarEventEntity deleted = existingEvent(true);
        deleted.setDeletedAt(LocalDateTime.now().minusDays(1));
        given(calendarRepository.findById(EVENT_ID)).willReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.deleteEvent(VILLAGE_ID, EVENT_ID, HEADMAN_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.CALENDAR_EVENT_NOT_FOUND);
    }

    // ========================================================================
    // 月別取得
    // ========================================================================

    @Test
    @DisplayName("月別取得: 毎年繰返・単発を含む並び（リポジトリの順序を尊重）")
    void listEventsByMonth_includesAnnualAndOneShot() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        VillageCalendarEventEntity annual = existingEvent(true); // 7/7 毎年
        VillageCalendarEventEntity oneShot = VillageCalendarEventEntity.builder()
                .villageId(VILLAGE_ID)
                .title("夏祭り2026")
                .eventDate(LocalDate.of(2026, 7, 20))
                .isAnnualRecurring(false)
                .createdByUserId(HEADMAN_USER_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        oneShot.setId(UUID.fromString("01956c00-0000-7000-8000-000000000bbb"));

        given(calendarRepository.findByMonth(eq(VILLAGE_ID), eq(2026), eq(7)))
                .willReturn(List.of(annual, oneShot));

        CalendarEventListResponse res = service.listEventsByMonth(VILLAGE_ID, 2026, 7);

        assertThat(res.year()).isEqualTo(2026);
        assertThat(res.month()).isEqualTo(7);
        assertThat(res.items()).hasSize(2);
        assertThat(res.items().get(0).title()).isEqualTo("七夕");
        assertThat(res.items().get(0).isAnnualRecurring()).isTrue();
        assertThat(res.items().get(1).title()).isEqualTo("夏祭り2026");
        assertThat(res.items().get(1).isAnnualRecurring()).isFalse();
    }

    @Test
    @DisplayName("月別取得: month 範囲外（0 や 13）は VILLAGE_FIELD_INVALID")
    void listEventsByMonth_invalidMonth() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));

        assertThatThrownBy(() -> service.listEventsByMonth(VILLAGE_ID, 2026, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);
        assertThatThrownBy(() -> service.listEventsByMonth(VILLAGE_ID, 2026, 13))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID);
    }

    // ========================================================================
    // 詳細取得
    // ========================================================================

    @Test
    @DisplayName("詳細取得: 削除済みイベントは VILLAGE_056")
    void getEvent_deleted() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        VillageCalendarEventEntity deleted = existingEvent(true);
        deleted.setDeletedAt(LocalDateTime.now().minusDays(1));
        given(calendarRepository.findById(EVENT_ID)).willReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.getEvent(VILLAGE_ID, EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.CALENDAR_EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("詳細取得: 生存イベントを返す")
    void getEvent_success() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village));
        given(calendarRepository.findById(EVENT_ID)).willReturn(Optional.of(existingEvent(true)));

        CalendarEventResponse res = service.getEvent(VILLAGE_ID, EVENT_ID);

        assertThat(res.id()).isEqualTo(EVENT_ID);
        assertThat(res.title()).isEqualTo("七夕");
        assertThat(res.createdByDisplayName()).isNull();
    }
}
