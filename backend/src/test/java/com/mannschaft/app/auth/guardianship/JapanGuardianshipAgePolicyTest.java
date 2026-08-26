package com.mannschaft.app.auth.guardianship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JapanGuardianshipAgePolicy} 境界テスト（F08.9 P3a）。
 *
 * <p>満12歳に達する年度の 3/31 まで切替可（{@code elementary}）、翌年度 4/1 以降封印（{@code junior_high}）。
 * 日本の学齢（4/1 生まれ＝前学年）を厳格に検証する。Clock は {@code Asia/Tokyo} 固定で date-pin
 * （CI を固定日付で塞がない）。</p>
 */
@DisplayName("JapanGuardianshipAgePolicy 境界テスト（F08.9 P3a）")
class JapanGuardianshipAgePolicyTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final JapanGuardianshipAgePolicy policy = new JapanGuardianshipAgePolicy();

    /** 指定 JST 日付の正午に固定した Clock を生成する。 */
    private Clock jstClockAt(LocalDate date) {
        return Clock.fixed(date.atTime(LocalTime.NOON).atZone(JST).toInstant(), JST);
    }

    @Nested
    @DisplayName("設計書必須ケース: 2013-04-02 生まれ")
    class Born20130402 {

        private final LocalDate birthDate = LocalDate.parse("2013-04-02");

        @Test
        @DisplayName("2026-03-31 基準 → switchAllowed=true / stageKey=elementary")
        void at_2026_03_31_allowed() {
            AgeStageResolution r = policy.resolve(birthDate, jstClockAt(LocalDate.parse("2026-03-31")));
            assertThat(r.switchAllowed()).isTrue();
            assertThat(r.stageKey()).isEqualTo("elementary");
        }

        @Test
        @DisplayName("2026-04-01 基準 → switchAllowed=false / stageKey=junior_high")
        void at_2026_04_01_blocked() {
            AgeStageResolution r = policy.resolve(birthDate, jstClockAt(LocalDate.parse("2026-04-01")));
            assertThat(r.switchAllowed()).isFalse();
            assertThat(r.stageKey()).isEqualTo("junior_high");
        }
    }

    @Nested
    @DisplayName("学齢 4/1 生まれ＝前学年（4/2 生まれより一年早く封印）")
    class Born0401PreviousGrade {

        private final LocalDate april1 = LocalDate.parse("2013-04-01");
        private final LocalDate april2 = LocalDate.parse("2013-04-02");

        @Test
        @DisplayName("4/1 生まれ: 2025-03-31 はまだ切替可")
        void april1_2025_03_31_allowed() {
            AgeStageResolution r = policy.resolve(april1, jstClockAt(LocalDate.parse("2025-03-31")));
            assertThat(r.switchAllowed()).isTrue();
        }

        @Test
        @DisplayName("4/1 生まれ: 2025-04-01 で封印（前学年のため 4/2 生まれより一年早い）")
        void april1_2025_04_01_blocked() {
            AgeStageResolution r = policy.resolve(april1, jstClockAt(LocalDate.parse("2025-04-01")));
            assertThat(r.switchAllowed()).isFalse();
            assertThat(r.stageKey()).isEqualTo("junior_high");
        }

        @Test
        @DisplayName("4/2 生まれ: 同じ 2025-04-01 ではまだ切替可（一学年下）")
        void april2_2025_04_01_stillAllowed() {
            AgeStageResolution r = policy.resolve(april2, jstClockAt(LocalDate.parse("2025-04-01")));
            assertThat(r.switchAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("年度境界の連続性")
    class FiscalBoundary {

        @Test
        @DisplayName("2014-04-01 生まれ: 2026-03-31 切替可 / 2026-04-01 封印")
        void born_2014_04_01() {
            LocalDate b = LocalDate.parse("2014-04-01");
            assertThat(policy.resolve(b, jstClockAt(LocalDate.parse("2026-03-31"))).switchAllowed()).isTrue();
            assertThat(policy.resolve(b, jstClockAt(LocalDate.parse("2026-04-01"))).switchAllowed()).isFalse();
        }

        @Test
        @DisplayName("2014-04-02 生まれ: 2026-04-01 でもまだ切替可")
        void born_2014_04_02_stillAllowed() {
            LocalDate b = LocalDate.parse("2014-04-02");
            assertThat(policy.resolve(b, jstClockAt(LocalDate.parse("2026-04-01"))).switchAllowed()).isTrue();
        }

        @Test
        @DisplayName("十分に幼い子（小学校入学前）は切替可")
        void youngChildAllowed() {
            LocalDate b = LocalDate.parse("2020-06-15");
            assertThat(policy.resolve(b, jstClockAt(LocalDate.parse("2026-06-15"))).switchAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("タイムゾーン非依存（Clock が UTC でも JST 年度で評価）")
    class ZoneIndependence {

        @Test
        @DisplayName("UTC ゾーンの Clock を渡しても JST の年度日で判定する")
        void utcClockReZonedToJst() {
            // JST 2026-04-01 00:30 = UTC 2026-03-31 15:30。UTC のままだと前年度に誤判定するが、
            // ポリシーが JST へ再ゾーンするため 4/1（封印）と正しく判定される。
            Instant instant = LocalDate.parse("2026-04-01").atTime(LocalTime.of(0, 30)).atZone(JST).toInstant();
            Clock utcClock = Clock.fixed(instant, ZoneId.of("UTC"));
            LocalDate birthDate = LocalDate.parse("2013-04-02");
            AgeStageResolution r = policy.resolve(birthDate, utcClock);
            assertThat(r.switchAllowed()).isFalse();
        }
    }

    @Test
    @DisplayName("supportedCountryCode は JP")
    void supportedCountryCode() {
        assertThat(policy.supportedCountryCode()).isEqualTo("JP");
    }

    @Nested
    @DisplayName("sealDate（封印境界日＝満12歳に達する年度の翌4/1・F08.9 P3c-2）")
    class SealDate {

        @Test
        @DisplayName("2013-04-02 生まれ → 境界日 2026-04-01（clock 非依存）")
        void born20130402_seal20260401() {
            LocalDate birthDate = LocalDate.parse("2013-04-02");
            assertThat(policy.sealDate(birthDate, jstClockAt(LocalDate.parse("2020-01-01"))))
                    .isEqualTo(LocalDate.parse("2026-04-01"));
            // clock を変えても結果は同じ（生年月日から一意）。
            assertThat(policy.sealDate(birthDate, jstClockAt(LocalDate.parse("2099-12-31"))))
                    .isEqualTo(LocalDate.parse("2026-04-01"));
        }

        @Test
        @DisplayName("4/1 生まれ＝前学年: 2013-04-01 生まれ → 境界日 2025-04-01（4/2 生まれより一年早い）")
        void born20130401_seal20250401() {
            assertThat(policy.sealDate(LocalDate.parse("2013-04-01"), jstClockAt(LocalDate.parse("2020-01-01"))))
                    .isEqualTo(LocalDate.parse("2025-04-01"));
            // 一日違いの 4/2 生まれは一年遅い境界。
            assertThat(policy.sealDate(LocalDate.parse("2013-04-02"), jstClockAt(LocalDate.parse("2020-01-01"))))
                    .isEqualTo(LocalDate.parse("2026-04-01"));
        }

        @Test
        @DisplayName("封印日当日に resolve が false へ変わる（境界日の整合）")
        void sealDateMatchesResolveBoundary() {
            LocalDate birthDate = LocalDate.parse("2013-04-02");
            LocalDate seal = policy.sealDate(birthDate, jstClockAt(LocalDate.parse("2020-01-01")));
            assertThat(seal).isEqualTo(LocalDate.parse("2026-04-01"));
            assertThat(policy.resolve(birthDate, jstClockAt(seal.minusDays(1))).switchAllowed()).isTrue();
            assertThat(policy.resolve(birthDate, jstClockAt(seal)).switchAllowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("3月内の複数日でオフバイワンなし（同一学年として年度末まで切替可）")
    class MarchMultipleDaysWithinSameGrade {

        /**
         * 対象: 2013-04-02 生まれ（2026年度 = 中学1年相当）。
         * 2026年3月は「まだ満12歳到達年度末の前」= 切替可能な最終学年の3月中。
         * 3/1・3/15・3/30・3/31 すべて switchAllowed=true・stageKey=elementary であること。
         */
        private final LocalDate birthDate = LocalDate.parse("2013-04-02");

        @Test
        @DisplayName("3/1 基準 → switchAllowed=true / stageKey=elementary")
        void march1_switchAllowed() {
            AgeStageResolution r = policy.resolve(birthDate, jstClockAt(LocalDate.parse("2026-03-01")));
            assertThat(r.switchAllowed()).isTrue();
            assertThat(r.stageKey()).isEqualTo("elementary");
        }

        @Test
        @DisplayName("3/15 基準 → switchAllowed=true / stageKey=elementary")
        void march15_switchAllowed() {
            AgeStageResolution r = policy.resolve(birthDate, jstClockAt(LocalDate.parse("2026-03-15")));
            assertThat(r.switchAllowed()).isTrue();
            assertThat(r.stageKey()).isEqualTo("elementary");
        }

        @Test
        @DisplayName("3/30 基準 → switchAllowed=true / stageKey=elementary")
        void march30_switchAllowed() {
            AgeStageResolution r = policy.resolve(birthDate, jstClockAt(LocalDate.parse("2026-03-30")));
            assertThat(r.switchAllowed()).isTrue();
            assertThat(r.stageKey()).isEqualTo("elementary");
        }

        @Test
        @DisplayName("3/31 基準（年度末当日）→ switchAllowed=true / stageKey=elementary")
        void march31_switchAllowed() {
            AgeStageResolution r = policy.resolve(birthDate, jstClockAt(LocalDate.parse("2026-03-31")));
            assertThat(r.switchAllowed()).isTrue();
            assertThat(r.stageKey()).isEqualTo("elementary");
        }

        @Test
        @DisplayName("4/1 基準（年度明け初日）→ switchAllowed=false / stageKey=junior_high（オフバイワンなし確認）")
        void april1_firstDayOfNewYear_blocked() {
            AgeStageResolution r = policy.resolve(birthDate, jstClockAt(LocalDate.parse("2026-04-01")));
            assertThat(r.switchAllowed()).isFalse();
            assertThat(r.stageKey()).isEqualTo("junior_high");
        }
    }
}
