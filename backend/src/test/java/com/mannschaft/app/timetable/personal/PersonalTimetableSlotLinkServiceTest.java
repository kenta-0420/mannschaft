package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.TimetableStatus;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSlotLinkService;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * F03.15 Phase 4 PersonalTimetableSlotLinkService のユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableSlotLinkService ユニットテスト")
class PersonalTimetableSlotLinkServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long PT_ID = 1L;
    private static final Long SLOT_ID = 11L;
    private static final Long TEAM_ID = 50L;
    private static final Long TT_ID = 200L;
    private static final Long LINKED_SLOT_ID = 314L;

    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private PersonalTimetableSlotRepository personalSlotRepository;
    @Mock private TimetableRepository timetableRepository;
    @Mock private TimetableSlotRepository timetableSlotRepository;
    @Mock private UserRoleRepository userRoleRepository;

    private PersonalTimetableSlotLinkService service;

    @BeforeEach
    void setUp() {
        service = new PersonalTimetableSlotLinkService(
                personalTimetableRepository,
                personalSlotRepository,
                timetableRepository,
                timetableSlotRepository,
                userRoleRepository);
    }

    private static PersonalTimetableEntity buildPersonal() {
        PersonalTimetableEntity p = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("test")
                .status(PersonalTimetableStatus.ACTIVE)
                .build();
        try {
            Field idField = p.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(p, PT_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return p;
    }

    private static PersonalTimetableSlotEntity buildSlot(String dow, int period) {
        PersonalTimetableSlotEntity s = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(PT_ID)
                .dayOfWeek(dow)
                .periodNumber(period)
                .weekPattern(WeekPattern.EVERY)
                .subjectName("ドイツ語")
                .autoSyncChanges(true)
                .build();
        try {
            Field idField = s.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(s, SLOT_ID);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return s;
    }

    private static TimetableEntity buildTimetable(TimetableStatus status, Long teamId) {
        return TimetableEntity.builder()
                .teamId(teamId)
                .name("チーム時間割")
                .status(status)
                .weekPatternEnabled(false)
                .build();
    }

    private static TimetableSlotEntity buildTtSlot(String dow, int period, Long ttId) {
        return TimetableSlotEntity.builder()
                .timetableId(ttId)
                .dayOfWeek(dow)
                .periodNumber(period)
                .weekPattern(WeekPattern.EVERY)
                .subjectName("ドイツ語")
                .build();
    }

    @Test
    @DisplayName("link: 正常系（auto_sync_changes 反映 + 保存）")
    void link_正常系() {
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(PT_ID, USER_ID))
                .willReturn(Optional.of(buildPersonal()));
        PersonalTimetableSlotEntity slot = buildSlot("MON", 2);
        given(personalSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(slot));
        given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(true);
        given(timetableRepository.findById(TT_ID))
                .willReturn(Optional.of(buildTimetable(TimetableStatus.ACTIVE, TEAM_ID)));
        given(timetableSlotRepository.findById(LINKED_SLOT_ID))
                .willReturn(Optional.of(buildTtSlot("MON", 2, TT_ID)));
        given(personalSlotRepository.save(any(PersonalTimetableSlotEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        var saved = service.link(PT_ID, SLOT_ID, USER_ID, TEAM_ID, TT_ID, LINKED_SLOT_ID, true);

        assertThat(saved.getLinkedTeamId()).isEqualTo(TEAM_ID);
        assertThat(saved.getLinkedTimetableId()).isEqualTo(TT_ID);
        assertThat(saved.getLinkedSlotId()).isEqualTo(LINKED_SLOT_ID);
        assertThat(saved.getAutoSyncChanges()).isTrue();
    }

    @Test
    @DisplayName("link: linked_team_id 未指定で 400")
    void link_チーム未指定() {
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(PT_ID, USER_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(personalSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(buildSlot("MON", 2)));

        assertThatThrownBy(() -> service.link(PT_ID, SLOT_ID, USER_ID, null, TT_ID, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_TIMETABLE_REQUIRED.getCode());
    }

    @Test
    @DisplayName("link: 非メンバーで 403")
    void link_非メンバー() {
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(PT_ID, USER_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(personalSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(buildSlot("MON", 2)));
        given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(false);

        assertThatThrownBy(() -> service.link(PT_ID, SLOT_ID, USER_ID, TEAM_ID, TT_ID, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_NOT_TEAM_MEMBER.getCode());
    }

    @Test
    @DisplayName("link: ACTIVE 以外の時間割で 409")
    void link_非ACTIVE時間割() {
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(PT_ID, USER_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(personalSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(buildSlot("MON", 2)));
        given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(true);
        given(timetableRepository.findById(TT_ID))
                .willReturn(Optional.of(buildTimetable(TimetableStatus.DRAFT, TEAM_ID)));

        assertThatThrownBy(() -> service.link(PT_ID, SLOT_ID, USER_ID, TEAM_ID, TT_ID, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_STATUS_INVALID.getCode());
    }

    @Test
    @DisplayName("link: linked_slot 曜日×時限不一致で 409")
    void link_位置不一致() {
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(PT_ID, USER_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(personalSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(buildSlot("MON", 2)));
        given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(true);
        given(timetableRepository.findById(TT_ID))
                .willReturn(Optional.of(buildTimetable(TimetableStatus.ACTIVE, TEAM_ID)));
        given(timetableSlotRepository.findById(LINKED_SLOT_ID))
                .willReturn(Optional.of(buildTtSlot("WED", 4, TT_ID)));

        assertThatThrownBy(() ->
                service.link(PT_ID, SLOT_ID, USER_ID, TEAM_ID, TT_ID, LINKED_SLOT_ID, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_POSITION_MISMATCH.getCode());
    }

    @Test
    @DisplayName("unlink: 正常系（リンク列が NULL クリア）")
    void unlink_正常系() {
        PersonalTimetableSlotEntity slot = buildSlot("MON", 2);
        slot.linkTo(TEAM_ID, TT_ID, LINKED_SLOT_ID, true);
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(PT_ID, USER_ID))
                .willReturn(Optional.of(buildPersonal()));
        given(personalSlotRepository.findById(SLOT_ID)).willReturn(Optional.of(slot));
        given(personalSlotRepository.save(any(PersonalTimetableSlotEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        service.unlink(PT_ID, SLOT_ID, USER_ID);

        assertThat(slot.getLinkedTeamId()).isNull();
        assertThat(slot.getLinkedTimetableId()).isNull();
        assertThat(slot.getLinkedSlotId()).isNull();
    }
}
