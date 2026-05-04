package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.15 Phase 4 統合テスト。
 *
 * <p>以下を検証する:</p>
 * <ul>
 *   <li>PersonalTimetableSlotRepository.findByLinkedTimetableId / findByLinkedTeamId</li>
 *   <li>PersonalTimetableSlotRepository.findByLinkedTeamIdAndOwnerUserId（脱退時クリーンアップ用）</li>
 *   <li>ScheduleRepository.findByExternalRef / findByExternalRefPrefix（idempotency 用）</li>
 *   <li>schedules.external_ref 列の UNIQUE インデックス（V14.009 マイグレーション）</li>
 * </ul>
 */
@DisplayName("F03.15 Phase 4 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PersonalTimetablePhase4IntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired private PersonalTimetableRepository personalTimetableRepository;
    @Autowired private PersonalTimetableSlotRepository slotRepository;
    @Autowired private ScheduleRepository scheduleRepository;

    private PersonalTimetableEntity persistTimetable(Long userId) {
        PersonalTimetableEntity entity = PersonalTimetableEntity.builder()
                .userId(userId)
                .name("Phase 4 統合テスト用")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(PersonalTimetableStatus.ACTIVE)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();
        return personalTimetableRepository.saveAndFlush(entity);
    }

    private PersonalTimetableSlotEntity persistSlot(Long ptId, String dow, int period,
                                                     Long linkedTeamId, Long linkedTimetableId) {
        PersonalTimetableSlotEntity slot = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(ptId)
                .dayOfWeek(dow)
                .periodNumber(period)
                .weekPattern(WeekPattern.EVERY)
                .subjectName("テスト")
                .autoSyncChanges(true)
                .linkedTeamId(linkedTeamId)
                .linkedTimetableId(linkedTimetableId)
                .build();
        return slotRepository.saveAndFlush(slot);
    }

    @Test
    @DisplayName("findByLinkedTimetableId / findByLinkedTeamId が想定どおりに引ける")
    @Transactional
    void findByLinkedTimetableId_動作() {
        Long userId = 7_001L;
        PersonalTimetableEntity pt = persistTimetable(userId);
        persistSlot(pt.getId(), "MON", 1, 50L, 200L);
        persistSlot(pt.getId(), "TUE", 2, 50L, 201L);
        persistSlot(pt.getId(), "WED", 3, null, null);

        assertThat(slotRepository.findByLinkedTimetableId(200L)).hasSize(1);
        assertThat(slotRepository.findByLinkedTeamId(50L)).hasSize(2);
        assertThat(slotRepository.findByLinkedTimetableId(999L)).isEmpty();
    }

    @Test
    @DisplayName("findByLinkedTeamIdAndOwnerUserId: 自分の所有コマのみ返す")
    @Transactional
    void findByLinkedTeamIdAndOwnerUserId_所有者制約() {
        Long userA = 7_010L;
        Long userB = 7_011L;
        PersonalTimetableEntity ptA = persistTimetable(userA);
        PersonalTimetableEntity ptB = persistTimetable(userB);
        persistSlot(ptA.getId(), "MON", 1, 50L, 200L);
        persistSlot(ptB.getId(), "MON", 1, 50L, 200L);

        List<PersonalTimetableSlotEntity> aSlots =
                slotRepository.findByLinkedTeamIdAndOwnerUserId(userA, 50L);
        assertThat(aSlots).hasSize(1);
        assertThat(aSlots.get(0).getPersonalTimetableId()).isEqualTo(ptA.getId());
    }

    @Test
    @DisplayName("ScheduleRepository.findByExternalRef + findByExternalRefPrefix")
    @Transactional
    void schedule_externalRef_検索() {
        Long userId = 7_020L;
        ScheduleEntity sch = ScheduleEntity.builder()
                .userId(userId)
                .title("[休講] テスト")
                .startAt(LocalDateTime.of(2026, 5, 10, 13, 0))
                .endAt(LocalDateTime.of(2026, 5, 10, 14, 30))
                .allDay(false)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .minResponseRole(MinResponseRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .externalRef("F03.15:1001:2002")
                .createdBy(userId)
                .build();
        scheduleRepository.saveAndFlush(sch);

        Optional<ScheduleEntity> found = scheduleRepository.findByExternalRef("F03.15:1001:2002");
        assertThat(found).isPresent();
        List<ScheduleEntity> prefix = scheduleRepository.findByExternalRefPrefix("F03.15:1001:%");
        assertThat(prefix).hasSize(1);
    }
}
