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
 * {@link CreateReminderRequest} のバリデーション単体テスト（機能55 第二陣）。
 * 相対/絶対の相互排他バリデーションと OffsetDateTime 対応を検証する。
 *
 * <p>FE は OffsetDateTime をユーザーのローカルTZ付きで送信する（例: 2026-06-04T08:00:00+09:00）。
 * BE は atZoneSameInstant(Asia/Tokyo) で JST LocalDateTime に変換して保存する。</p>
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
        @DisplayName("未来のremindAt（UTC+09:00）あり_違反なし")
        void 未来のremindAtあり_JSTオフセット_違反なし() {
            // JST（+09:00）で未来の日時を送信するケース
            CreateReminderRequest req = new CreateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("未来のremindAt（UTC）あり_違反なし")
        void 未来のremindAtあり_UTCオフセット_違反なし() {
            // UTC（+00:00）で未来の日時を送信するケース（非JSTユーザー）
            CreateReminderRequest req = new CreateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("未来のremindAt（UTC-05:00: EST）あり_違反なし")
        void 未来のremindAtあり_マイナスオフセット_違反なし() {
            // EST（-05:00）で未来の日時を送信するケース（アメリカ東部ユーザー）
            CreateReminderRequest req = new CreateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.ofHours(-5)).plusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("kind未指定でもABSOLUTE扱い_未来remindAtあり_違反なし")
        void kind未指定_ABSOLUTE扱い_違反なし() {
            CreateReminderRequest req = new CreateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1), null, null);
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
        @DisplayName("remindAtが過去（JST）_違反あり")
        void remindAtが過去_違反あり() {
            CreateReminderRequest req = new CreateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.ofHours(9)).minusDays(1), null, ReminderKind.ABSOLUTE);
            Set<ConstraintViolation<CreateReminderRequest>> violations = validator.validate(req);
            assertThat(violations).isNotEmpty();
        }

        @Test
        @DisplayName("remindAtが過去（UTC+00）_違反あり")
        void remindAtが過去_UTC_違反あり() {
            // UTC基準でも過去ならNG（タイムゾーンに依らず絶対過去を拒否）
            CreateReminderRequest req = new CreateReminderRequest(
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null, ReminderKind.ABSOLUTE);
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
