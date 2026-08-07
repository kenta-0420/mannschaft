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
        return birthDate.isAfter(adultBirthDateThreshold(baseDate));
    }

    /**
     * 「基準日時点で成人（18歳以上）」となる生年月日の上限を返す。
     *
     * <p>生年月日が戻り値<b>以前（同日を含む）</b>であれば成人である。誕生日当日に18歳へ
     * 到達した者を成人に含めるため、境界は閉区間（{@code birthDate <= threshold}）とする。</p>
     *
     * <p>本メソッドは {@link #isMinor(LocalDate, LocalDate)} と<b>同一の判定</b>を
     * バッチの絞り込み条件へ落とし込むための唯一の変換口である。</p>
     *
     * <p><b>注意: この閾値を SQL の {@code WHERE} 句で {@code users.birth_date} と
     * 直接比較してはならない。</b>{@code birth_date} は {@code EncryptedStringConverter} により
     * AES-256-GCM（ランダム IV）で暗号化されて格納されており、SQL 上の比較は暗号文同士の
     * バイト比較にしかならず日付順とは無関係である。SQL で使えるのは平文・索引付きの
     * {@code users.birth_year} だけであり、戻り値の<b>年</b>による粗い絞り込み
     * （成人を取りこぼさないが境界年の未成年が混ざる）と、復号済み生年月日に対する
     * 本メソッド／{@link #isMinor(LocalDate, LocalDate)} による確定判定を組み合わせること。</p>
     *
     * @param baseDate 基準日（通常は今日）
     * @return 成人と判定される生年月日の上限（この日を含む）
     */
    public static LocalDate adultBirthDateThreshold(LocalDate baseDate) {
        LocalDate candidate = baseDate.minusYears(ADULT_AGE);
        // うるう日生まれの補正: 2月29日生まれは平年では2月28日に満年齢へ到達する。
        // minusYears は 2/29 → 2/28 に丸めるため、1日進めてもなお満18歳に達しているなら
        // そちらが真の上限である（例: baseDate=2026-02-28 のとき上限は 2008-02-29）。
        LocalDate next = candidate.plusDays(1);
        return !next.plusYears(ADULT_AGE).isAfter(baseDate) ? next : candidate;
    }

    /** 成人年齢（民法上の成年）。保護者同意の要否判定に使用する。*/
    private static final int ADULT_AGE = 18;
}
