package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShiftAssignmentRepository#findUpcomingByUserIdBetween} 番人テスト（司令塔第二弾）。
 *
 * <p>個人ダッシュボード「今後の予定」（{@code GET /api/v1/dashboard/upcoming-events}）へ本人の
 * CONFIRMED シフト割当を統合するための期間指定クエリ。{@code [fromDate, untilDate)} の半開区間で
 * スロット日付を絞り込み、タイトルはスケジュール（{@link ShiftScheduleEntity#getTitle()}）から取得する。</p>
 *
 * <p>フィクスチャは {@code ShiftSlotEntity.builder()} の {@code LocalDate}/{@code LocalTime} を
 * 直接 bind しており、文字列リテラルによる TZ 罠（{@code feedback_it_fixture_datetime_tz_bind}）を
 * 回避している。</p>
 *
 * <p>検証観点:</p>
 * <ul>
 *   <li>期間内（開始日含む・終了日は排他的上限）の CONFIRMED 割当は含まれる</li>
 *   <li>PROPOSED / REVOKED は含まれない</li>
 *   <li>別ユーザーの割当は含まれない（AC-B2-2 認可の裏取り）</li>
 *   <li>返却は {@code [id, scheduleTitle, slotDate, startTime, endTime, teamId]} の {@code Object[]}</li>
 *   <li>並び順は日付→開始時刻の昇順</li>
 * </ul>
 */
@Transactional
@DisplayName("ShiftAssignmentRepository#findUpcomingByUserIdBetween 番人テスト（司令塔第二弾）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ShiftAssignmentRepositoryUpcomingTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ShiftAssignmentRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long USER_ID = 6001L;
    private static final Long OTHER_USER_ID = 6002L;
    private static final Long TEAM_ID = 9101L;
    private static final Long ASSIGNED_BY = 6099L;

    private static final LocalDate FROM_DATE = LocalDate.of(2026, 4, 1);
    private static final LocalDate UNTIL_DATE = LocalDate.of(2026, 4, 8); // 7日間の排他的上限

    private Long persistSchedule(String title, LocalDate startDate, LocalDate endDate) {
        ShiftScheduleEntity schedule = ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title(title)
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(startDate)
                .endDate(endDate)
                .status(ShiftScheduleStatus.PUBLISHED)
                .build();
        em.persist(schedule);
        em.flush();
        return schedule.getId();
    }

    private Long persistSlot(Long scheduleId, LocalDate slotDate, LocalTime startTime) {
        ShiftSlotEntity slot = ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(slotDate)
                .startTime(startTime)
                .endTime(startTime.plusHours(1))
                .build();
        em.persist(slot);
        em.flush();
        return slot.getId();
    }

    private void persistAssignment(Long userId, Long slotId, ShiftAssignmentStatus status) {
        ShiftAssignmentEntity assignment = ShiftAssignmentEntity.builder()
                .slotId(slotId)
                .userId(userId)
                .status(status)
                .assignedBy(ASSIGNED_BY)
                .build();
        em.persist(assignment);
        em.flush();
    }

    private List<Object[]> findUpcomingBetween() {
        em.clear();
        return repository.findUpcomingByUserIdBetween(USER_ID, FROM_DATE, UNTIL_DATE);
    }

    @Nested
    @DisplayName("期間の絞り込み（半開区間）")
    class DateRangeFilter {

        @Test
        @DisplayName("期間内（開始日含む）の CONFIRMED 割当は含まれる")
        void 期間開始日は含まれる() {
            Long scheduleId = persistSchedule("4月第1週シフト", FROM_DATE, UNTIL_DATE.minusDays(1));
            Long slotId = persistSlot(scheduleId, FROM_DATE, LocalTime.of(9, 0));
            persistAssignment(USER_ID, slotId, ShiftAssignmentStatus.CONFIRMED);

            assertThat(findUpcomingBetween()).hasSize(1);
        }

        @Test
        @DisplayName("期間の排他的上限（untilDate ちょうど）は含まれない")
        void 期間終了日は含まれない() {
            Long scheduleId = persistSchedule("4月第2週シフト", UNTIL_DATE, UNTIL_DATE.plusDays(6));
            Long slotId = persistSlot(scheduleId, UNTIL_DATE, LocalTime.of(9, 0));
            persistAssignment(USER_ID, slotId, ShiftAssignmentStatus.CONFIRMED);

            assertThat(findUpcomingBetween()).isEmpty();
        }

        @Test
        @DisplayName("期間より前・期間より後の枠は含まれない")
        void 期間外は含まれない() {
            Long scheduleA = persistSchedule("3月シフト", FROM_DATE.minusDays(7), FROM_DATE.minusDays(1));
            Long scheduleB = persistSchedule("4月第3週シフト", UNTIL_DATE.plusDays(1), UNTIL_DATE.plusDays(7));
            persistAssignment(USER_ID, persistSlot(scheduleA, FROM_DATE.minusDays(1), LocalTime.of(9, 0)),
                    ShiftAssignmentStatus.CONFIRMED);
            persistAssignment(USER_ID, persistSlot(scheduleB, UNTIL_DATE.plusDays(1), LocalTime.of(9, 0)),
                    ShiftAssignmentStatus.CONFIRMED);

            assertThat(findUpcomingBetween()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ステータス・ユーザー絞り込み")
    class StatusAndUserFilter {

        @Test
        @DisplayName("PROPOSED / REVOKED は含まれない（CONFIRMED のみ）")
        void 未確定取消は含まれない() {
            Long scheduleId = persistSchedule("4月第1週シフト", FROM_DATE, UNTIL_DATE.minusDays(1));
            persistAssignment(USER_ID, persistSlot(scheduleId, FROM_DATE, LocalTime.of(9, 0)),
                    ShiftAssignmentStatus.PROPOSED);
            persistAssignment(USER_ID, persistSlot(scheduleId, FROM_DATE.plusDays(1), LocalTime.of(9, 0)),
                    ShiftAssignmentStatus.REVOKED);

            assertThat(findUpcomingBetween()).isEmpty();
        }

        @Test
        @DisplayName("別ユーザーの割当は含まれない（AC-B2-2 認可の裏取り）")
        void 別ユーザーは含まれない() {
            Long scheduleId = persistSchedule("4月第1週シフト", FROM_DATE, UNTIL_DATE.minusDays(1));
            persistAssignment(OTHER_USER_ID, persistSlot(scheduleId, FROM_DATE, LocalTime.of(9, 0)),
                    ShiftAssignmentStatus.CONFIRMED);

            assertThat(findUpcomingBetween()).isEmpty();
        }
    }

    @Nested
    @DisplayName("返却形・並び順")
    class ShapeAndOrdering {

        @Test
        @DisplayName("結果は [id, scheduleTitle, slotDate, startTime, endTime, teamId] の Object[] で、日付→開始時刻昇順")
        void 返却形と並び順() {
            Long scheduleId = persistSchedule("4月第1週シフト", FROM_DATE, UNTIL_DATE.minusDays(1));
            Long later = persistSlot(scheduleId, FROM_DATE.plusDays(2), LocalTime.of(9, 0));
            Long earlier = persistSlot(scheduleId, FROM_DATE.plusDays(1), LocalTime.of(9, 0));
            persistAssignment(USER_ID, later, ShiftAssignmentStatus.CONFIRMED);
            persistAssignment(USER_ID, earlier, ShiftAssignmentStatus.CONFIRMED);

            List<Object[]> result = findUpcomingBetween();

            assertThat(result).hasSize(2);
            Object[] first = result.get(0);
            assertThat((String) first[1]).isEqualTo("4月第1週シフト");
            assertThat((LocalDate) first[2]).isEqualTo(FROM_DATE.plusDays(1));
            assertThat((LocalTime) first[3]).isEqualTo(LocalTime.of(9, 0));
            assertThat((LocalTime) first[4]).isEqualTo(LocalTime.of(10, 0));
            assertThat((Long) first[5]).isEqualTo(TEAM_ID);
        }
    }
}
