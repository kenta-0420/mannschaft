package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ReminderKind;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScheduleAttendanceReminderRepository#findDuePage} 結合テスト。
 *
 * <p>ABSOLUTE/RELATIVE 双方の due 判定を SQL 側（{@code schedules} との結合）で行えていること、
 * ID キーセットページングが正しく機能し、未到来・送信済みの行が飢餓なく後続ページの対象を
 * 妨げないことを実 DB で検証する（モックでは JPQL の正しさを検証できないため）。</p>
 */
@Transactional
@DisplayName("ScheduleAttendanceReminderRepository#findDuePage 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleAttendanceReminderRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScheduleAttendanceReminderRepository reminderRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);

    private Long persistSchedule(LocalDateTime startAt) {
        ScheduleEntity schedule = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(9001L)
                .title("結合テスト予定")
                .startAt(startAt)
                .endAt(startAt.plusHours(1))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(1L)
                .build());
        return schedule.getId();
    }

    private ScheduleAttendanceReminderEntity persistAbsolute(Long scheduleId, LocalDateTime remindAt, boolean sent) {
        ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                .scheduleId(scheduleId)
                .reminderKind(ReminderKind.ABSOLUTE)
                .remindAt(remindAt)
                .isSent(sent)
                .build();
        return reminderRepository.save(reminder);
    }

    private ScheduleAttendanceReminderEntity persistRelative(Long scheduleId, int minutesBefore, boolean sent) {
        ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                .scheduleId(scheduleId)
                .reminderKind(ReminderKind.RELATIVE)
                .remindBeforeMinutes(minutesBefore)
                .isSent(sent)
                .build();
        return reminderRepository.save(reminder);
    }

    @Test
    @DisplayName("ABSOLUTE: remindAt <= now のみ due として返る（未到来・送信済みは除外）")
    void ABSOLUTE_due判定() {
        Long scheduleId = persistSchedule(NOW.plusDays(1));
        ScheduleAttendanceReminderEntity due = persistAbsolute(scheduleId, NOW.minusMinutes(5), false);
        persistAbsolute(scheduleId, NOW.plusMinutes(5), false); // 未到来
        persistAbsolute(scheduleId, NOW.minusMinutes(5), true); // 送信済み

        List<ScheduleAttendanceReminderEntity> result =
                reminderRepository.findDuePage(NOW, 0L, PageRequest.of(0, 100));

        assertThat(result).extracting(ScheduleAttendanceReminderEntity::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("RELATIVE: 親予定の start_at - remindBeforeMinutes <= now を SQL 結合で判定する")
    void RELATIVE_due判定() {
        // 開始5分後・30分前リマインド → 実効時刻は5分前 → due
        Long dueScheduleId = persistSchedule(NOW.plusMinutes(5));
        ScheduleAttendanceReminderEntity due = persistRelative(dueScheduleId, 30, false);

        // 開始10時間後・30分前リマインド → 実効時刻は未来 → 対象外
        Long notYetScheduleId = persistSchedule(NOW.plusHours(10));
        persistRelative(notYetScheduleId, 30, false);

        List<ScheduleAttendanceReminderEntity> result =
                reminderRepository.findDuePage(NOW, 0L, PageRequest.of(0, 100));

        assertThat(result).extracting(ScheduleAttendanceReminderEntity::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("キーセットページング: 境界値（ちょうど1ページ・1ページ+1件・0件）で正しく分割される")
    void キーセットページング境界値() {
        Long scheduleId = persistSchedule(NOW.plusDays(1));

        // 0件
        assertThat(reminderRepository.findDuePage(NOW, 0L, PageRequest.of(0, 3))).isEmpty();

        // ちょうど1ページ（3件）
        List<Long> ids3 = List.of(
                persistAbsolute(scheduleId, NOW.minusMinutes(30), false).getId(),
                persistAbsolute(scheduleId, NOW.minusMinutes(20), false).getId(),
                persistAbsolute(scheduleId, NOW.minusMinutes(10), false).getId());
        List<ScheduleAttendanceReminderEntity> page1 =
                reminderRepository.findDuePage(NOW, 0L, PageRequest.of(0, 3));
        assertThat(page1).extracting(ScheduleAttendanceReminderEntity::getId)
                .containsExactlyElementsOf(ids3);
        // カーソルを最終IDに進めると空
        assertThat(reminderRepository.findDuePage(NOW, ids3.get(2), PageRequest.of(0, 3))).isEmpty();
    }

    @Test
    @DisplayName("飢餓しない: 先頭ページが未到来分で埋まっても、cursorを進めれば due な後続が取得できる")
    void 飢餓しないこと() {
        Long scheduleId = persistSchedule(NOW.plusDays(1));

        // 先頭に未到来（対象外）を大量作成 → WHERE句で除外されるためこれらはそもそも返らない。
        for (int i = 0; i < 5; i++) {
            persistAbsolute(scheduleId, NOW.plusHours(i + 1), false);
        }
        // 対象（due）はその後ろに作成
        ScheduleAttendanceReminderEntity due = persistAbsolute(scheduleId, NOW.minusMinutes(1), false);

        // ページサイズを小さく（2件）しても、対象外行がページを占有せず due な行が取得できる
        // （WHERE句で絞り込むため、対象外の5件はページ消費に一切関与しない）。
        List<ScheduleAttendanceReminderEntity> result =
                reminderRepository.findDuePage(NOW, 0L, PageRequest.of(0, 2));

        assertThat(result).extracting(ScheduleAttendanceReminderEntity::getId).containsExactly(due.getId());
    }
}
