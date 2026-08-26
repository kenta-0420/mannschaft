package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
import com.mannschaft.app.timetable.personal.dto.TimetableWeekSlotInfo;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
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
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link PersonalTimetableDashboardService#listAllWeekSlots} 単体テスト（F06.5 Phase 2・§11.3 AC-30）。
 *
 * <p>カバー AC-30: 全曜日・dedup・PERSONAL/TEAM 混在・subjectName 空除外・母集合（ACTIVE effective）。
 * AC-31: courseCode 相違で別候補。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableDashboardService#listAllWeekSlots 単体テスト（F06.5 Phase 2）")
class PersonalTimetableDashboardServiceLinkableSlotsTest {

    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private PersonalTimetableSlotRepository personalSlotRepository;
    @Mock private PersonalTimetablePeriodRepository personalPeriodRepository;
    @Mock private TimetableRepository teamTimetableRepository;
    @Mock private TimetableSlotRepository teamSlotRepository;
    @Mock private TimetableSlotUserNoteRepository userNoteRepository;
    @Mock private TimetableSlotUserNoteAttachmentRepository attachmentRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private TimetableChangeRepository timetableChangeRepository;

    @InjectMocks private PersonalTimetableDashboardService service;

    private static final Long USER_ID = 100L;
    private static final LocalDate TODAY = LocalDate.now();

    private static void setId(Object entity, long id) {
        try {
            // BaseEntity の id フィールドを reflection で設定
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PersonalTimetableEntity activePersonal(long id) {
        PersonalTimetableEntity e = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("個人時間割")
                .effectiveFrom(TODAY.minusDays(30))
                .status(PersonalTimetableStatus.ACTIVE)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();
        setId(e, id);
        return e;
    }

    private PersonalTimetableSlotEntity personalSlot(long id, String dayOfWeek, int periodNumber,
                                                      String subjectName, String courseCode,
                                                      String teacherName) {
        PersonalTimetableSlotEntity e = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(1L)
                .dayOfWeek(dayOfWeek)
                .periodNumber(periodNumber)
                .subjectName(subjectName)
                .courseCode(courseCode)
                .teacherName(teacherName)
                .build();
        setId(e, id);
        return e;
    }

    private PersonalTimetablePeriodEntity period(int periodNumber, String label) {
        PersonalTimetablePeriodEntity e = PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(1L)
                .periodNumber(periodNumber)
                .label(label)
                .startTime(LocalTime.of(8, 50))
                .endTime(LocalTime.of(10, 20))
                .build();
        return e;
    }

    @Test
    @DisplayName("AC-30: 全曜日スロットが dedup されて返る（同一科目の複数コマは1候補）")
    void listAllWeekSlots_deduplicatesSameSubject() {
        PersonalTimetableEntity personal = activePersonal(1L);
        given(personalTimetableRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                eq(USER_ID), eq(PersonalTimetableStatus.ACTIVE)))
                .willReturn(List.of(personal));

        // 同じ科目「数学I」が月曜1限と水曜1限の2スロット
        PersonalTimetableSlotEntity slot1 = personalSlot(10L, "MON", 1, "数学I", "MA101", "田中先生");
        PersonalTimetableSlotEntity slot2 = personalSlot(20L, "WED", 1, "数学I", "MA101", "田中先生");
        given(personalSlotRepository.findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(1L))
                .willReturn(List.of(slot1, slot2));
        given(personalPeriodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(1L))
                .willReturn(List.of(period(1, "1限")));
        given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

        List<TimetableWeekSlotInfo> result = service.listAllWeekSlots(USER_ID, TODAY);

        // dedup により「数学I(MA101)」が1件のみ
        assertThat(result).hasSize(1);
        assertThat(result.get(0).subjectName()).isEqualTo("数学I");
        assertThat(result.get(0).courseCode()).isEqualTo("MA101");
        assertThat(result.get(0).kind()).isEqualTo("PERSONAL");
    }

    @Test
    @DisplayName("AC-30: subjectName が空・NULL のスロットは候補から除外される")
    void listAllWeekSlots_excludesEmptySubjectName() {
        PersonalTimetableEntity personal = activePersonal(1L);
        given(personalTimetableRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                eq(USER_ID), eq(PersonalTimetableStatus.ACTIVE)))
                .willReturn(List.of(personal));

        // subjectName が空・null のスロット
        PersonalTimetableSlotEntity emptySlot = personalSlot(10L, "MON", 1, "", null, null);
        PersonalTimetableSlotEntity nullSlot = personalSlot(20L, "TUE", 1, null, null, null);
        PersonalTimetableSlotEntity validSlot = personalSlot(30L, "WED", 1, "英語", null, null);
        given(personalSlotRepository.findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(1L))
                .willReturn(List.of(emptySlot, nullSlot, validSlot));
        given(personalPeriodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(1L))
                .willReturn(List.of());
        given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

        List<TimetableWeekSlotInfo> result = service.listAllWeekSlots(USER_ID, TODAY);

        // 有効な subjectName を持つスロットのみ
        assertThat(result).hasSize(1);
        assertThat(result.get(0).subjectName()).isEqualTo("英語");
    }

    @Test
    @DisplayName("AC-31: courseCode 相違（数学1 vs 数学A）は別候補として区別される")
    void listAllWeekSlots_differentCourseCodes_areDistinctCandidates() {
        PersonalTimetableEntity personal = activePersonal(1L);
        given(personalTimetableRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                eq(USER_ID), eq(PersonalTimetableStatus.ACTIVE)))
                .willReturn(List.of(personal));

        // 同名科目「数学」で courseCode 違い（MA101 vs MA201）
        PersonalTimetableSlotEntity slot1 = personalSlot(10L, "MON", 1, "数学", "MA101", null);
        PersonalTimetableSlotEntity slot2 = personalSlot(20L, "TUE", 1, "数学", "MA201", null);
        given(personalSlotRepository.findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(1L))
                .willReturn(List.of(slot1, slot2));
        given(personalPeriodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(1L))
                .willReturn(List.of());
        given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

        List<TimetableWeekSlotInfo> result = service.listAllWeekSlots(USER_ID, TODAY);

        // courseCode 相違で2候補
        assertThat(result).hasSize(2);
        assertThat(result).extracting(TimetableWeekSlotInfo::courseCode)
                .containsExactlyInAnyOrder("MA101", "MA201");
    }

    @Test
    @DisplayName("AC-30: 時間割未登録（ACTIVE なし）の場合は空リスト返却")
    void listAllWeekSlots_noActiveTimetable_returnsEmpty() {
        given(personalTimetableRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                eq(USER_ID), eq(PersonalTimetableStatus.ACTIVE)))
                .willReturn(List.of());
        given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

        List<TimetableWeekSlotInfo> result = service.listAllWeekSlots(USER_ID, TODAY);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AC-30: effective 期間外の個人時間割は母集合に含まれない")
    void listAllWeekSlots_expiredTimetable_excluded() {
        // effectiveUntil が昨日 → 本日有効でない
        PersonalTimetableEntity expired = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("古い時間割")
                .effectiveFrom(TODAY.minusDays(60))
                .effectiveUntil(TODAY.minusDays(1))  // 昨日まで有効
                .status(PersonalTimetableStatus.ACTIVE)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();
        setId(expired, 1L);

        given(personalTimetableRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                eq(USER_ID), eq(PersonalTimetableStatus.ACTIVE)))
                .willReturn(List.of(expired));
        given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());

        List<TimetableWeekSlotInfo> result = service.listAllWeekSlots(USER_ID, TODAY);

        assertThat(result).isEmpty();
    }
}
