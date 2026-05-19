package com.mannschaft.app.common.util;

import com.mannschaft.app.auth.AgeGroup;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 年齢グループ計算ユーティリティ。
 * 日本の学年制度（4月2日 cutoff）に基づいて AgeGroup を算出する。
 * F01.9 年齢確認・保護者同意機能でクロスドメイン利用されるため、common.util パッケージに配置する。
 */
public final class AgeGroupCalculator {

    private AgeGroupCalculator() {
    }

    /**
     * 学年基準日（4月1日時点の年齢）で AgeGroup を計算する。
     * 日本の学校教育法施行規則に基づき、4月2日生まれは翌年度扱い。
     *
     * @param birthDate 生年月日
     * @param baseDate  基準日（通常は今日）
     * @return AgeGroup
     */
    public static AgeGroup calculate(LocalDate birthDate, LocalDate baseDate) {
        // 基準日が含まれる年度の4月1日を求める
        LocalDate fiscalStart = baseDate.getMonthValue() >= 4
            ? LocalDate.of(baseDate.getYear(), 4, 1)
            : LocalDate.of(baseDate.getYear() - 1, 4, 1);
        int age = (int) ChronoUnit.YEARS.between(birthDate, fiscalStart);
        return switch (age) {
            case 6, 7       -> AgeGroup.ELEMENTARY_LOWER;
            case 8, 9       -> AgeGroup.ELEMENTARY_MIDDLE;
            case 10, 11     -> AgeGroup.ELEMENTARY_UPPER;
            case 12, 13, 14 -> AgeGroup.JUNIOR_HIGH;
            case 15, 16, 17 -> AgeGroup.SENIOR_HIGH;
            default -> age >= 18 ? AgeGroup.ADULT : AgeGroup.ELEMENTARY_LOWER;
        };
    }

    /**
     * カレンダー年齢（実年齢）で18歳未満かどうかを判定する。
     * 保護者同意フロー起動の閾値として使用する。
     * 学年基準ではなく実年齢で判定することに注意。
     *
     * @param birthDate 生年月日
     * @param baseDate  基準日（通常はメール認証日時）
     * @return true if 18歳未満
     */
    public static boolean isMinor(LocalDate birthDate, LocalDate baseDate) {
        return ChronoUnit.YEARS.between(birthDate, baseDate) < 18;
    }
}
