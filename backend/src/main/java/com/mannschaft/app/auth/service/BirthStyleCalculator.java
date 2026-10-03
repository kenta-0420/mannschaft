package com.mannschaft.app.auth.service;

import java.time.LocalDate;
import org.springframework.stereotype.Service;

/** 本人出生情報をメモリ内で派生数へ変換する。未実装の試練用骨格。 */
@Service
public class BirthStyleCalculator {
    public BirthNumbers calculate(LocalDate date, String lastNameKana, String firstNameKana) {
        throw new UnsupportedOperationException("出生計算は未実装");
    }
    public String romanize(String kana) {
        throw new UnsupportedOperationException("カナ変換は未実装");
    }
    /** 元姓名・カナ・日付を含めない派生値のみ。 */
    public record BirthNumbers(int lifePathNumber, int nameNumber, int dateSum, int nameSum,
                               String normalizationVersion, String ruleVersion) {}
}
