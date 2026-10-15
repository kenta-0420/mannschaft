package com.mannschaft.app.schedule.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CreatePersonalScheduleRequest} のリマインダーバリデーション単体テスト（機能55 第二陣）。
 * 相対・絶対の合算上限（最大5件）を検証する。
 * absoluteReminders は OffsetDateTime で受け取る（タイムゾーン情報を保持）。
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
                                                List<OffsetDateTime> absoluteReminders) {
        return new CreatePersonalScheduleRequest(
                "個人予定", null, null,
                OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1),
                OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1).plusHours(1),
                false, null, null,
                reminders, absoluteReminders, null);
    }

    @Test
    @DisplayName("相対2件_絶対2件_合計4件_違反なし")
    void 合計4件_違反なし() {
        CreatePersonalScheduleRequest req = build(
                List.of(10, 30),
                List.of(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1),
                        OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(2)));
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("相対3件_絶対3件_合計6件_合算上限違反あり")
    void 合計6件_違反あり() {
        CreatePersonalScheduleRequest req = build(
                List.of(10, 20, 30),
                List.of(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1),
                        OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(2),
                        OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(3)));
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("reminderCountWithinLimit"));
    }

    @Test
    @DisplayName("絶対指定の過去日時_@Future制約削除済みのため違反なし")
    void 絶対指定が過去_違反なし() {
        // OffsetDateTime化に伴い @Future 制約を削除済み。
        // バッチ側（PersonalScheduleReminderService）が未送信判定するため、保存時は過去日時も許容する。
        CreatePersonalScheduleRequest req = build(
                null, List.of(OffsetDateTime.now(ZoneOffset.ofHours(9)).minusDays(1)));
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("リマインダー未指定_違反なし")
    void 未指定_違反なし() {
        CreatePersonalScheduleRequest req = build(null, null);
        Set<ConstraintViolation<CreatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
