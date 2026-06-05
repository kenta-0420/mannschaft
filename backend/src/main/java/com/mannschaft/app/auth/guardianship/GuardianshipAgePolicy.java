package com.mannschaft.app.auth.guardianship;

import java.time.Clock;
import java.time.LocalDate;

/**
 * F08.9 P3a 後見切替の年齢段階ポリシー（国別ストラテジ）。
 *
 * <p>初等教育の終了年齢・自立段階の境界は国によって異なるため、しきい値を焼き付けず
 * 国別実装で解決する（03_security §3.1）。{@link GuardianshipAgePolicyRegistry} が
 * {@code country_code} に応じた実装を選択する。</p>
 *
 * <p>判定は復号済みの {@link LocalDate} 生年月日と注入された {@link Clock} のみで行い、
 * 「今日」は {@code Clock} から導出する（date-pin によるテスト固定が可能・CI を塞がない）。</p>
 */
public interface GuardianshipAgePolicy {

    /**
     * 生年月日と基準時計から後見切替の可否・段階を解決する。
     *
     * @param birthDate 子の生年月日（復号済み・null 不可）
     * @param clock     基準時計（テスト時は {@link Clock#fixed} で固定する）
     * @return 切替可否（{@code switchAllowed}）と段階キー（{@code stageKey}）
     */
    AgeStageResolution resolve(LocalDate birthDate, Clock clock);

    /**
     * このポリシーが対応する ISO 3166-1 alpha-2 国コード（例: {@code "JP"}）。
     * フォールバック実装は {@code null} を返してよい。
     *
     * @return 対応国コード（フォールバックは null）
     */
    String supportedCountryCode();
}
