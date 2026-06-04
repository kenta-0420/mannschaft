package com.mannschaft.app.schedule.dto;

import com.mannschaft.app.schedule.ReminderKind;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpdateReminderRequest} のバリデーション単体テスト（機能55 編集対応）。
 *
 * <p>編集コンテキストでは絶対指定の過去日時を許容する（未来日時制約なし）。
 * ABSOLUTE の場合は remindAt の非null のみを検証し、
 * RELATIVE の場合は remindBeforeMinutes の正値を検証する。
 * remindAt は OffsetDateTime で受け取る（タイムゾーン情報を保持）。</p>
 */
@DisplayName("UpdateReminderRequest バリデーションテスト")
class UpdateReminderRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Nested
    @DisplayName("ABSOLUTE（絶対指定）")
    class Absolute {

        @Test
        @DisplayName("未来のremindAt（JST）あり_違反なし")
        void 未来のremindAtあり_違反なし() {
            UpdateReminderRequest req = new UpdateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("過去のremindAt（JST）あり_編集コンテキストでは違反なし")
        void 過去のremindAtあり_違反なし() {
            // 編集時は既存リマインダーが過去日時になっている場合があるため許容する
            UpdateReminderRequest req = new UpdateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.ofHours(9)).minusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("過去のremindAt（UTC）あり_編集コンテキストでは違反なし")
        void 過去のremindAt_UTC_違反なし() {
            UpdateReminderRequest req = new UpdateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("kind未指定でもABSOLUTE扱い_remindAtあり_違反なし")
        void kind未指定_ABSOLUTE扱い_違反なし() {
            UpdateReminderRequest req = new UpdateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1), null, null);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("remindAtなし_違反あり")
        void remindAtなし_違反あり() {
            UpdateReminderRequest req = new UpdateReminderRequest(null, null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("RELATIVE（相対指定）")
    class Relative {

        @Test
        @DisplayName("正のremindBeforeMinutesあり_違反なし")
        void 正のminutesあり_違反なし() {
            UpdateReminderRequest req = new UpdateReminderRequest(null, 30, ReminderKind.RELATIVE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("remindBeforeMinutesなし_違反あり")
        void minutesなし_違反あり() {
            UpdateReminderRequest req = new UpdateReminderRequest(null, null, ReminderKind.RELATIVE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("remindBeforeMinutesが0以下_違反あり")
        void minutesが0以下_違反あり() {
            UpdateReminderRequest req = new UpdateReminderRequest(null, 0, ReminderKind.RELATIVE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("RELATIVEではremindAt欠如でも違反なし")
        void RELATIVEではremindAt欠如でも違反なし() {
            UpdateReminderRequest req = new UpdateReminderRequest(null, 15, ReminderKind.RELATIVE);
            Set<ConstraintViolation<UpdateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }
    }
}
