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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpdatePersonalScheduleRequest} の absoluteReminders フィールドのバリデーションテスト。
 *
 * <p>機能55 BE対応: absoluteReminders の null 許容・OffsetDateTime対応を検証する。
 * null = 変更なし、空リスト = 絶対リマインダー全削除のセマンティクス。
 * OffsetDateTime化に伴い @Future 制約は削除済み（過去日時も許容）。</p>
 */
@DisplayName("UpdatePersonalScheduleRequest absoluteReminders バリデーションテスト")
class UpdatePersonalScheduleRequestReminderTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private UpdatePersonalScheduleRequest buildWithAbsoluteReminders(List<OffsetDateTime> absoluteReminders) {
        return new UpdatePersonalScheduleRequest(
                null, null, null, null, null, null, null, null,
                null, null, null, absoluteReminders);
    }

    @Test
    @DisplayName("absoluteReminders=null_変更なしセマンティクス_違反なし")
    void absoluteReminders_null_違反なし() {
        UpdatePersonalScheduleRequest req = buildWithAbsoluteReminders(null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("absoluteReminders=空リスト_全削除セマンティクス_違反なし")
    void absoluteReminders_空リスト_違反なし() {
        UpdatePersonalScheduleRequest req = buildWithAbsoluteReminders(Collections.emptyList());
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("absoluteReminders=未来1件（JST）_違反なし")
    void absoluteReminders_未来1件_違反なし() {
        UpdatePersonalScheduleRequest req = buildWithAbsoluteReminders(
                List.of(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1)));
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("absoluteReminders=未来3件（UTC）_違反なし")
    void absoluteReminders_未来3件_違反なし() {
        UpdatePersonalScheduleRequest req = buildWithAbsoluteReminders(
                List.of(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(2),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(3)));
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    @DisplayName("absoluteReminders=過去日時含む_編集コンテキストでは違反なし")
    void absoluteReminders_過去日時_編集では違反なし() {
        // 編集時は既存リマインダーが過去日時になっている場合があるため @Future 制約なし
        UpdatePersonalScheduleRequest req = buildWithAbsoluteReminders(
                List.of(OffsetDateTime.now(ZoneOffset.ofHours(9)).minusDays(1)));
        Set<ConstraintViolation<UpdatePersonalScheduleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
