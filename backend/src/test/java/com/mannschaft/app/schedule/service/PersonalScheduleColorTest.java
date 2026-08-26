package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CalendarColorSource;
import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F03.19 W1-c — 個人スケジュール一覧への色付与（AC-08c・R1 裁定）の単体テスト。
 *
 * <p>設計書 §3.4.1: 個人予定もカレンダー面に並ぶ以上、色体系の外に置かない。
 * 解決順は <b>レイヤー色（{@code PERSONAL:0}）&gt; 予定色 {@code schedules.color} &gt; 自動色</b>。
 * カテゴリは個人予定に紐づかないため 3 段（カテゴリ色は登場しない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F03.19 個人スケジュール一覧の色（W1-c・AC-08c）")
class PersonalScheduleColorTest {

    private static final Long ME = 1001L;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 31, 23, 59);

    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private PersonalScheduleReminderRepository reminderRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private ScheduleRecurrenceService recurrenceService;
    @Mock
    private ScheduleAccessGuard scheduleAccessGuard;
    @Mock
    private CalendarLayerService calendarLayerService;

    private PersonalScheduleService service;

    @BeforeEach
    void setUp() {
        service = new PersonalScheduleService(
                scheduleRepository, reminderRepository, eventPublisher, new ObjectMapper(),
                nameResolverService, recurrenceService, scheduleAccessGuard, calendarLayerService);
        when(calendarLayerService.findUserLayerColors(anyLong())).thenReturn(Map.of());
        when(nameResolverService.resolveUserDisplayName(anyLong())).thenReturn("私");
    }

    @Test
    @DisplayName("AC-08c PERSONAL レイヤー色が予定色より優先して content.color に載る")
    void layerColorWinsOverScheduleColor() {
        givenSchedules(personal("#00FF00"));
        when(calendarLayerService.findUserLayerColors(ME)).thenReturn(Map.of("PERSONAL:0", "#DC2626"));

        PersonalScheduleResponse.PersonalContentDto content = listFirst();

        assertThat(content.color()).isEqualTo("#DC2626");
        assertThat(content.colorSource()).isEqualTo(CalendarColorSource.LAYER_USER);
    }

    @Test
    @DisplayName("レイヤー色が無ければ予定色が効く")
    void scheduleColorWhenNoLayerColor() {
        givenSchedules(personal("#00FF00"));

        PersonalScheduleResponse.PersonalContentDto content = listFirst();

        assertThat(content.color()).isEqualTo("#00FF00");
        assertThat(content.colorSource()).isEqualTo(CalendarColorSource.SCHEDULE);
    }

    @Test
    @DisplayName("どちらも無ければ PERSONAL:0 の自動色（FE の '#22C55E' フォールバックを置き換える）")
    void autoColorWhenNothingSet() {
        givenSchedules(personal(null));

        PersonalScheduleResponse.PersonalContentDto content = listFirst();

        assertThat(content.color()).isEqualTo(CalendarLayerAutoColor.resolve("PERSONAL", 0L));
        assertThat(content.colorSource()).isEqualTo(CalendarColorSource.LAYER_AUTO);
    }

    @Test
    @DisplayName("一覧の全件で color / colorSource が非 null（埋め忘れ経路が無い）")
    void everyListedEntryHasColor() {
        givenSchedules(personal("#00FF00"), personal(null));

        List<PersonalScheduleResponse> list =
                service.listPersonalSchedules(ME, FROM, TO, null, null, null, 50);

        assertThat(list).hasSize(2);
        assertThat(list).allSatisfy(r -> {
            assertThat(r.getContent().color()).isNotNull();
            assertThat(r.getContent().colorSource()).isNotNull();
        });
    }

    @Test
    @DisplayName("【陰性対照】詳細 GET は編集用の生値を返す（解決色で上書きしない）")
    void detailKeepsRawColor() {
        ScheduleEntity entity = personal(null);
        when(scheduleRepository.findById(1L)).thenReturn(java.util.Optional.of(entity));
        when(reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(anyLong()))
                .thenReturn(List.of());
        when(calendarLayerService.findUserLayerColors(ME)).thenReturn(Map.of("PERSONAL:0", "#DC2626"));

        PersonalScheduleResponse detail = service.getPersonalSchedule(1L, ME);

        assertThat(detail.getContent().color()).isNull();
        assertThat(detail.getContent().colorSource()).isNull();
    }

    // ------------------------------------------------------------------

    private PersonalScheduleResponse.PersonalContentDto listFirst() {
        return service.listPersonalSchedules(ME, FROM, TO, null, null, null, 50)
                .get(0).getContent();
    }

    private void givenSchedules(ScheduleEntity... entities) {
        when(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(ME), any(), any()))
                .thenReturn(List.of(entities));
    }

    private ScheduleEntity personal(String color) {
        return ScheduleEntity.builder()
                .id(1L)
                .userId(ME)
                .title("個人予定")
                .startAt(FROM.plusHours(1)).endAt(FROM.plusHours(2)).allDay(false)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .color(color)
                .createdBy(ME)
                .build();
    }
}
