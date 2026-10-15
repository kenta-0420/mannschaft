package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.listener.PersonalTimetableSlotMembershipRevokeListener;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 4 PersonalTimetableSlotMembershipRevokeListener のユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableSlotMembershipRevokeListener ユニットテスト")
class PersonalTimetableSlotMembershipRevokeListenerTest {

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 50L;

    @Mock private PersonalTimetableSlotRepository personalSlotRepository;

    private PersonalTimetableSlotMembershipRevokeListener listener;

    @BeforeEach
    void setUp() {
        listener = new PersonalTimetableSlotMembershipRevokeListener(personalSlotRepository);
    }

    private static PersonalTimetableSlotEntity buildLinkedSlot() {
        PersonalTimetableSlotEntity s = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(1L)
                .dayOfWeek("MON")
                .periodNumber(2)
                .weekPattern(WeekPattern.EVERY)
                .subjectName("test")
                .autoSyncChanges(true)
                .linkedTeamId(TEAM_ID)
                .linkedTimetableId(200L)
                .linkedSlotId(11L)
                .build();
        return s;
    }

    @Test
    @DisplayName("REMOVED + TEAM スコープで該当コマのリンクを NULL クリア")
    void TEAM_REMOVED_リンク解除() {
        PersonalTimetableSlotEntity slot = buildLinkedSlot();
        given(personalSlotRepository.findByLinkedTeamIdAndOwnerUserId(USER_ID, TEAM_ID))
                .willReturn(List.of(slot));

        listener.onMembershipChanged(new MembershipChangedEvent(
                USER_ID, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.REMOVED));

        assertThat(slot.getLinkedTeamId()).isNull();
        assertThat(slot.getLinkedTimetableId()).isNull();
        assertThat(slot.getLinkedSlotId()).isNull();
        verify(personalSlotRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("ASSIGNED は対象外")
    void ASSIGNED_スキップ() {
        listener.onMembershipChanged(new MembershipChangedEvent(
                USER_ID, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.ASSIGNED));
        verify(personalSlotRepository, never()).findByLinkedTeamIdAndOwnerUserId(any(), any());
    }

    @Test
    @DisplayName("ORGANIZATION スコープは対象外")
    void ORG_スコープスキップ() {
        listener.onMembershipChanged(new MembershipChangedEvent(
                USER_ID, "ORGANIZATION", TEAM_ID, MembershipChangedEvent.ChangeType.REMOVED));
        verify(personalSlotRepository, never()).findByLinkedTeamIdAndOwnerUserId(any(), any());
    }

}
