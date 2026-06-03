package com.mannschaft.app.schedule.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpdateScheduleRequest} のリマインダー・予約タスク拡張フィールドのバリデーションテスト。
 *
 * <p>機能55 BE対応: reminders/scheduledSurveys/scheduledAttendance の
 * null許容・サイズ制約・各フィールドの部分更新セマンティクスを検証する。</p>
 */
@DisplayName("UpdateScheduleRequest リマインダー拡張バリデーションテスト")
class UpdateScheduleRequestReminderTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ========================================
    // reminders フィールド
    // ========================================

    // コンストラクタ引数: title, description, location, startAt, endAt, allDay,
    //   eventType, visibility, minViewRole, minResponseRole, attendanceRequired, attendanceDeadline,
    //   commentOption, eventCategoryId, academicYear, updateScope, reminders, scheduledSurveys, scheduledAttendance
    // = 19引数

    @Test
    @DisplayName("reminders=null_変更なしセマンティクス_違反なし")
    void reminders_null_違反なし() {
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        Set<ConstraintViolation<UpdateScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("reminders=空リスト_全削除セマンティクス_違反なし")
    void reminders_空リスト_違反なし() {
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, Collections.emptyList(), null, null);
        Set<ConstraintViolation<UpdateScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("reminders=5件_上限内_違反なし")
    void reminders_5件_違反なし() {
        List<CreateReminderRequest> reminders = List.of(
                new CreateReminderRequest(null, 10, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 20, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 30, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 60, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 120, com.mannschaft.app.schedule.ReminderKind.RELATIVE));
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, reminders, null, null);
        Set<ConstraintViolation<UpdateScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("reminders=6件_上限超過_違反あり")
    void reminders_6件_違反あり() {
        List<CreateReminderRequest> reminders = List.of(
                new CreateReminderRequest(null, 10, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 20, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 30, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 60, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 120, com.mannschaft.app.schedule.ReminderKind.RELATIVE),
                new CreateReminderRequest(null, 180, com.mannschaft.app.schedule.ReminderKind.RELATIVE));
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, reminders, null, null);
        Set<ConstraintViolation<UpdateScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("reminders"));
    }

    // ========================================
    // scheduledSurveys フィールド
    // ========================================

    @Test
    @DisplayName("scheduledSurveys=null_変更なしセマンティクス_違反なし")
    void scheduledSurveys_null_違反なし() {
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("scheduledSurveys=空リスト_全削除セマンティクス_違反なし")
    void scheduledSurveys_空リスト_違反なし() {
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, Collections.emptyList(), null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("scheduledSurveys=11件_上限超過_違反あり")
    void scheduledSurveys_11件_違反あり() {
        List<ScheduledSurveyRequest> surveys = Collections.nCopies(11, null);
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, surveys, null);
        Set<ConstraintViolation<UpdateScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("scheduledSurveys"));
    }

    // ========================================
    // scheduledAttendance フィールド
    // ========================================

    @Test
    @DisplayName("scheduledAttendance=null_変更なしセマンティクス_違反なし")
    void scheduledAttendance_null_違反なし() {
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("scheduledAttendance=非null_違反なし")
    void scheduledAttendance_非null_違反なし() {
        ScheduledAttendanceRequest attendance = new ScheduledAttendanceRequest(
                LocalDateTime.now().plusDays(1), null, null, null);
        UpdateScheduleRequest req = new UpdateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, attendance);
        assertThat(validator.validate(req)).isEmpty();
    }
}
