package com.mannschaft.app.auth.guardianship;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * F08.9 P3a 日本の後見切替年齢ポリシー（既定）。
 *
 * <p>判定基準（03_security §3.1・御裁可済 §9 #11-1）:</p>
 * <ul>
 *   <li>満12歳に達する<b>年度の3月末（3/31）まで</b> {@code switchAllowed=true}（小学生・{@code stageKey="elementary"}）。</li>
 *   <li>翌年度の<b>4/1 以降</b> {@code switchAllowed=false}（中学生以降・封印・{@code stageKey="junior_high"}）。</li>
 * </ul>
 *
 * <h3>日本の学齢（4/1 生まれ＝前学年）の扱い</h3>
 * <p>学校教育法施行規則に基づき、年齢は「その年度の 4/1 時点の満年齢」で数える（学齢）。
 * 4/1 生まれの子は前日 3/31 に満年齢が上がるため<b>一学年上（前学年）</b>として扱われ、
 * 4/2 生まれの子より一年早く封印される。これは
 * {@code ChronoUnit.YEARS.between(birthDate, 当該年度の4/1)} を数えることで自然に表現される
 * （4/1 生まれは 4/1 時点でちょうど満年齢が成立し、4/2 生まれは 1 日足りず前年齢のまま）。</p>
 *
 * <p>すなわち「年度4/1時点の学齢」が 12 未満の間だけ切替可、12 以上で封印。
 * これは {@code AgeGroupCalculator.calculate} の学年算出（4/2 cutoff）と同じ規則であり、
 * JUNIOR_HIGH 進学＝学齢 12 と一致する。</p>
 *
 * <h3>境界日（年度末）の解釈タイムゾーン</h3>
 * <p>日本の年度境界（3/31→4/1）は JST で評価する。注入された {@link Clock} のゾーンに依存せず
 * 正しい年度日になるよう、内部で {@code Asia/Tokyo} に再ゾーンして「今日」を導出する。</p>
 */
@Component
public class JapanGuardianshipAgePolicy implements GuardianshipAgePolicy {

    /** 対応国コード（ISO 3166-1 alpha-2）。 */
    public static final String COUNTRY_CODE = "JP";

    /** 中学進学＝学齢がこの値以上で切替を封印する（満12歳に達する年度の翌年度4/1）。 */
    private static final int JUNIOR_HIGH_SCHOOL_AGE = 12;

    /** 年度境界を評価するタイムゾーン（日本の年度＝4/1 始まり）。 */
    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    /** 小学生段階（切替可）の i18n ラベルキー。 */
    private static final String STAGE_ELEMENTARY = "elementary";

    /** 中学生以降段階（封印）の i18n ラベルキー。 */
    private static final String STAGE_JUNIOR_HIGH = "junior_high";

    @Override
    public AgeStageResolution resolve(LocalDate birthDate, Clock clock) {
        if (birthDate == null) {
            throw new IllegalArgumentException("birthDate must not be null for guardianship age resolution");
        }
        // 年度境界は JST で評価する（Clock のゾーンに依存させない）。
        LocalDate today = LocalDate.now(clock.withZone(ZONE_JST));
        LocalDate fiscalYearStart = fiscalYearStart(today);

        // 当該年度の 4/1 時点の学齢（満年齢）。4/1 生まれは前学年として一年早く繰り上がる。
        long schoolAge = ChronoUnit.YEARS.between(birthDate, fiscalYearStart);

        if (schoolAge < JUNIOR_HIGH_SCHOOL_AGE) {
            // 満12歳に達する年度の 3/31 まで：小学生・切替可。
            return new AgeStageResolution(true, STAGE_ELEMENTARY);
        }
        // 翌年度 4/1 以降：中学生以降・封印。
        return new AgeStageResolution(false, STAGE_JUNIOR_HIGH);
    }

    @Override
    public LocalDate sealDate(LocalDate birthDate, Clock clock) {
        if (birthDate == null) {
            throw new IllegalArgumentException("birthDate must not be null for guardianship seal date resolution");
        }
        // 封印は「年度4/1時点の学齢が 12 に達する年度」の 4/1 で発火する。
        // 学齢は満年齢（誕生日基準）の年度始め評価なので、満12歳に達する日（birthDate+12年）以降で
        // 最初に来る 4/1 が境界日。clock には依存しない（生年月日から一意に定まる）。
        LocalDate twelfthBirthday = birthDate.plusYears(JUNIOR_HIGH_SCHOOL_AGE);
        return firstApril1OnOrAfter(twelfthBirthday);
    }

    @Override
    public String supportedCountryCode() {
        return COUNTRY_CODE;
    }

    /**
     * 指定日以降（当日含む）で最初に来る 4/1 を返す。
     * 4/1 ちょうどならその日、それ以外は次の年（または当年）の 4/1。
     */
    private LocalDate firstApril1OnOrAfter(LocalDate date) {
        LocalDate april1ThisYear = LocalDate.of(date.getYear(), 4, 1);
        return date.isAfter(april1ThisYear)
                ? LocalDate.of(date.getYear() + 1, 4, 1)
                : april1ThisYear;
    }

    /**
     * 指定日が属する年度の 4/1（年度始まり）を返す。
     * 1〜3 月は前年の 4/1、4〜12 月は当年の 4/1。
     */
    private LocalDate fiscalYearStart(LocalDate date) {
        return date.getMonthValue() >= 4
                ? LocalDate.of(date.getYear(), 4, 1)
                : LocalDate.of(date.getYear() - 1, 4, 1);
    }
}
