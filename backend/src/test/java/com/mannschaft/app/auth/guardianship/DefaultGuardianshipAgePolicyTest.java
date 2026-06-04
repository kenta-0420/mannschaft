package com.mannschaft.app.auth.guardianship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultGuardianshipAgePolicy} 境界テスト（F08.9 P3a フォールバック）。
 *
 * <p>未対応国向け安全側ポリシー: 満13歳の誕生日で封印（誕生日前日まで切替可）。
 * Clock 固定で date-pin（CI を固定日付で塞がない）。</p>
 */
@DisplayName("DefaultGuardianshipAgePolicy 境界テスト（F08.9 P3a フォールバック）")
class DefaultGuardianshipAgePolicyTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final DefaultGuardianshipAgePolicy policy = new DefaultGuardianshipAgePolicy();

    /** 指定日の正午に固定した Clock を生成する。 */
    private Clock clockAt(LocalDate date) {
        return Clock.fixed(date.atTime(LocalTime.NOON).atZone(UTC).toInstant(), UTC);
    }

    @Test
    @DisplayName("満13歳誕生日の前日 → switchAllowed=true / stageKey=minor")
    void dayBefore13thBirthday_allowed() {
        LocalDate birthDate = LocalDate.parse("2013-06-15");
        // 13歳の誕生日は 2026-06-15。前日 2026-06-14 は切替可。
        AgeStageResolution r = policy.resolve(birthDate, clockAt(LocalDate.parse("2026-06-14")));
        assertThat(r.switchAllowed()).isTrue();
        assertThat(r.stageKey()).isEqualTo("minor");
    }

    @Test
    @DisplayName("満13歳誕生日の当日 → switchAllowed=false / stageKey=independent")
    void on13thBirthday_blocked() {
        LocalDate birthDate = LocalDate.parse("2013-06-15");
        AgeStageResolution r = policy.resolve(birthDate, clockAt(LocalDate.parse("2026-06-15")));
        assertThat(r.switchAllowed()).isFalse();
        assertThat(r.stageKey()).isEqualTo("independent");
    }

    @Test
    @DisplayName("満13歳誕生日の翌日 → 引き続き封印")
    void dayAfter13thBirthday_blocked() {
        LocalDate birthDate = LocalDate.parse("2013-06-15");
        AgeStageResolution r = policy.resolve(birthDate, clockAt(LocalDate.parse("2026-06-16")));
        assertThat(r.switchAllowed()).isFalse();
    }

    @Test
    @DisplayName("十分に幼い子は切替可")
    void youngChildAllowed() {
        LocalDate birthDate = LocalDate.parse("2020-01-01");
        AgeStageResolution r = policy.resolve(birthDate, clockAt(LocalDate.parse("2026-01-01")));
        assertThat(r.switchAllowed()).isTrue();
    }

    @Test
    @DisplayName("うるう年 2/29 生まれ: 13歳の 2/29 が無い年は 3/1 で封印（plusYears の規約）")
    void leapDayBirth() {
        LocalDate birthDate = LocalDate.parse("2012-02-29");
        // 2012-02-29 + 13年 = 2025-02-28（2025 はうるう年でない）。
        assertThat(policy.resolve(birthDate, clockAt(LocalDate.parse("2025-02-27"))).switchAllowed()).isTrue();
        assertThat(policy.resolve(birthDate, clockAt(LocalDate.parse("2025-02-28"))).switchAllowed()).isFalse();
    }

    @Test
    @DisplayName("supportedCountryCode はフォールバックなので null")
    void supportedCountryCodeNull() {
        assertThat(policy.supportedCountryCode()).isNull();
    }
}
