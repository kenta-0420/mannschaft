package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteAttachmentRepository;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteRepository;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 回帰: 当日の個人コマが全て未リンク（linked_timetable_id = null）のとき、
 * {@link PersonalTimetableDashboardService#getTimetableToday} が NPE にならないこと。
 *
 * <p>臨時変更の一括取得結果が空だと {@code Map.of()} が返り、null キーでの
 * {@code getOrDefault} は NPE を投げる。個人時間割だけを使う利用者の画面が 500 になる経路。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableDashboardService#getTimetableToday 未リンクコマ回帰")
class PersonalTimetableDashboardUnlinkedSlotTest {

    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private PersonalTimetableSlotRepository personalSlotRepository;
    @Mock private PersonalTimetablePeriodRepository personalPeriodRepository;
    @Mock private TimetableRepository teamTimetableRepository;
    @Mock private TimetableSlotRepository teamSlotRepository;
    @Mock private TimetableSlotUserNoteRepository userNoteRepository;
    @Mock private TimetableSlotUserNoteAttachmentRepository attachmentRepository;
    @Mock private MembershipScopeQueryService membershipScopeQueryService;
    @Mock private TimetableChangeRepository timetableChangeRepository;

    @InjectMocks private PersonalTimetableDashboardService service;

    private static final Long USER_ID = 100L;
    // 2026-11-02 は月曜
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private static void setId(Object entity, long id) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalStateException("id フィールドが見つからない");
    }

    @Test
    @DisplayName("全コマ未リンクでも NPE にならず、変更なしの個人コマが返る")
    void allUnlinkedSlotsDoNotThrow() throws Exception {
        PersonalTimetableEntity personal = PersonalTimetableEntity.builder()
                .userId(USER_ID).name("個人時間割").effectiveFrom(TODAY.minusDays(30))
                .status(PersonalTimetableStatus.ACTIVE).visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false).build();
        setId(personal, 1L);
        given(personalTimetableRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                eq(USER_ID), eq(PersonalTimetableStatus.ACTIVE))).willReturn(List.of(personal));

        PersonalTimetableSlotEntity slot = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(1L).dayOfWeek("MON").periodNumber(1)
                .weekPattern(WeekPattern.EVERY).subjectName("数学I").build();
        setId(slot, 10L);
        given(personalSlotRepository.findByPersonalTimetableIdInAndDayOfWeekOrderByPeriodNumberAsc(
                any(), eq("MON"))).willReturn(List.of(slot));
        given(membershipScopeQueryService.findActiveTeamIds(USER_ID)).willReturn(List.of());

        DashboardTimetableTodayResponse res = service.getTimetableToday(USER_ID, TODAY);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).isChanged()).isFalse();
    }
}
