package com.mannschaft.app.schedule.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CreatePersonalScheduleRequest} のリマインダーバリデーション単体テスト（機能55 第二陣）。
 * 相対・絶対の合算上限（最大5件）と絶対指定の未来日時制約を検証する。
 */
@DisplayName("CreatePersonalScheduleRequest リマインダーバリデーションテスト")
class CreatePersonalScheduleRequestReminderTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private CreatePersonalScheduleRequest build(List<Integer> reminders,
                                                List<LocalDateTime> absoluteReminders) {
        return new CreatePersonalScheduleRequest(
                "個人予定", null, null,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(1),
                false, null, null,
                reminders, absoluteReminders, null);
    }

    @Test
    @DisplayName("相対2件_絶対2件_合計4件_違反なし")
    void 合計4件_違反なし() {
        CreatePersonalScheduleRequest req = build(
                List.of(10, 30),
                List.of(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2)));
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("相対3件_絶対3件_合計6件_合算上限違反あり")
    void 合計6件_違反あり() {
        CreatePersonalScheduleRequest req = build(
                List.of(10, 20, 30),
                List.of(LocalDateTime.now().plusDays(1),
                        LocalDateTime.now().plusDays(2),
                        LocalDateTime.now().plusDays(3)));
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("reminderCountWithinLimit"));
    }

    @Test
    @DisplayName("絶対指定が過去日時_違反あり")
    void 絶対指定が過去_違反あり() {
        CreatePersonalScheduleRequest req = build(
                null, List.of(LocalDateTime.now().minusDays(1)));
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("リマインダー未指定_違反なし")
    void 未指定_違反なし() {
        CreatePersonalScheduleRequest req = build(null, null);
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
