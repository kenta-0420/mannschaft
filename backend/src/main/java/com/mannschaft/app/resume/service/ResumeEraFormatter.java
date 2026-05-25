package com.mannschaft.app.resume.service;

import com.mannschaft.app.resume.entity.ResumeEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.chrono.JapaneseDate;
import java.time.chrono.JapaneseEra;
import java.time.temporal.ChronoField;

/**
 * 西暦 / 和暦 変換ユーティリティ（F01.10）。
 *
 * <p>設計書: {@code docs/features/F01.10_mypage_resume.md} §7.2
 *
 * <p>対応元号: 令和（2019-05-01〜）/ 平成（1989-01-08〜2019-04-30）/ 昭和（1926-12-25〜1989-01-07）
 *
 * <p>改元年で月が NULL の場合の決定的フォールバック規則:
 * <ul>
 *   <li>1989 年 → 平成（1/8〜12/31 が平成 358 日 > 昭和 7 日）</li>
 *   <li>2019 年 → 令和（5/1〜12/31 が令和 245 日 > 平成 120 日）</li>
 * </ul>
 */
@Component
public class ResumeEraFormatter {

    /**
     * 月を確定させるための改元年フォールバック用の月。
     * 「多数派」の元号に確実に属する月を選ぶ。
     * 1989 年は 2 月（平成元年に確実に属する）、2019 年は 6 月（令和元年に確実に属する）。
     */
    private static final int HEISEI_FALLBACK_MONTH = 2;   // 1989-02 → 平成
    private static final int REIWA_FALLBACK_MONTH  = 6;   // 2019-06 → 令和

    /**
     * 年・月を {@code era_format} に従い文字列化する。
     *
     * <ul>
     *   <li>{@link ResumeEntity.EraFormat#WESTERN}: "2018年4月" 形式</li>
     *   <li>{@link ResumeEntity.EraFormat#JAPANESE}: "平成30年4月" 形式</li>
     * </ul>
     *
     * @param year      年（必須）
     * @param month     月（null 可。null の場合は年のみ返す）
     * @param eraFormat 元号フォーマット
     * @return フォーマット済み文字列
     */
    public String formatYearMonth(int year, Integer month, ResumeEntity.EraFormat eraFormat) {
        if (eraFormat == ResumeEntity.EraFormat.WESTERN) {
            if (month == null) {
                return year + "年";
            }
            return year + "年" + month + "月";
        }

        // 和暦変換
        String eraLabel = resolveEraLabel(year, month);
        if (month == null) {
            return eraLabel;
        }
        return eraLabel + month + "月";
    }

    /**
     * 年のみの変換（月なし）。
     *
     * @param year      年
     * @param eraFormat 元号フォーマット
     * @return フォーマット済み文字列
     */
    public String formatYear(int year, ResumeEntity.EraFormat eraFormat) {
        return formatYearMonth(year, null, eraFormat);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 年・月から和暦表記（元号＋年数）を組み立てて返す。
     * 月が null の場合は改元年フォールバック規則を適用する。
     *
     * @param year  西暦年
     * @param month 月（null 可）
     * @return "令和X年" / "平成X年" / "昭和X年" 形式の文字列
     */
    private String resolveEraLabel(int year, Integer month) {
        int effectiveMonth = resolveEffectiveMonth(year, month);

        // 月が確定したため LocalDate を生成して JapaneseDate に変換する
        // 改元年フォールバックでは日を 1 日固定にしても元号は変わらない（月で確定しているため）
        LocalDate localDate = LocalDate.of(year, effectiveMonth, 1);
        JapaneseDate japaneseDate = JapaneseDate.from(localDate);

        JapaneseEra era = japaneseDate.getEra();
        // ChronoField.YEAR_OF_ERA で元年からの通算年を取得する
        int eraYear = japaneseDate.get(ChronoField.YEAR_OF_ERA);

        String eraName = toEraName(era);
        if (eraYear == 1) {
            return eraName + "元年";
        }
        return eraName + eraYear + "年";
    }

    /**
     * 月が null の場合に改元年フォールバック規則を適用し、確定した月を返す。
     * それ以外はそのまま返す。
     */
    private int resolveEffectiveMonth(int year, Integer month) {
        if (month != null) {
            return month;
        }
        // 改元年フォールバック
        if (year == 1989) {
            return HEISEI_FALLBACK_MONTH; // 2月 → 平成
        }
        if (year == 2019) {
            return REIWA_FALLBACK_MONTH;  // 6月 → 令和
        }
        // 通常年は月なしの場合 1 月として算出（JapaneseDate の取得用途のみ）
        return 1;
    }

    /**
     * {@link JapaneseEra} を日本語元号名に変換する。
     * Java の JapaneseEra は令和（REIWA）・平成（HEISEI）・昭和（SHOWA）・大正（TAISHO）・明治（MEIJI）に対応。
     */
    private String toEraName(JapaneseEra era) {
        return switch (era.getValue()) {
            case 3  -> "令和";   // REIWA  (value=3, 2019-05-01〜)
            case 2  -> "平成";   // HEISEI (value=2, 1989-01-08〜2019-04-30)
            case 1  -> "昭和";   // SHOWA  (value=1, 1926-12-25〜1989-01-07)
            case 0  -> "大正";   // TAISHO (value=0)
            case -1 -> "明治";   // MEIJI  (value=-1)
            default -> "不明";
        };
    }
}
