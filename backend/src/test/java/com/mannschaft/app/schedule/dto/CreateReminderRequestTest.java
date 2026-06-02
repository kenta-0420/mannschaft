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

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CreateReminderRequest} のバリデーション単体テスト（機能55 第二陣）。
 * 相対/絶対の相互排他バリデーションを検証する。
 */
@DisplayName("CreateReminderRequest バリデーションテスト")
class CreateReminderRequestTest {

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
        @DisplayName("未来のremindAtあり_違反なし")
        void 未来のremindAtあり_違反なし() {
            CreateReminderRequest req = new CreateReminderRequest(
                    LocalDateTime.now().plusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("kind未指定でもABSOLUTE扱い_未来remindAtあり_違反なし")
        void kind未指定_ABSOLUTE扱い_違反なし() {
            CreateReminderRequest req = new CreateReminderRequest(
                    LocalDateTime.now().plusDays(1), null, null);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("remindAtなし_違反あり")
        void remindAtなし_違反あり() {
            CreateReminderRequest req = new CreateReminderRequest(null, null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("remindAtが過去_違反あり")
        void remindAtが過去_違反あり() {
            CreateReminderRequest req = new CreateReminderRequest(
                    LocalDateTime.now().minusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("RELATIVE（相対指定）")
    class Relative {

        @Test
        @DisplayName("正のremindBeforeMinutesあり_違反なし")
        void 正のminutesあり_違反なし() {
            CreateReminderRequest req = new CreateReminderRequest(null, 30, ReminderKind.RELATIVE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("remindBeforeMinutesなし_違反あり")
        void minutesなし_違反あり() {
            CreateReminderRequest req = new CreateReminderRequest(null, null, ReminderKind.RELATIVE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("remindBeforeMinutesが0以下_違反あり")
        void minutesが0以下_違反あり() {
            CreateReminderRequest req = new CreateReminderRequest(null, 0, ReminderKind.RELATIVE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("RELATIVEではremindAt欠如でも違反なし")
        void RELATIVEではremindAt欠如でも違反なし() {
            CreateReminderRequest req = new CreateReminderRequest(null, 15, ReminderKind.RELATIVE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }
    }
}
