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
     * 後見切替が封印される境界日（その日以降 {@code switchAllowed=false} に変わる最初の日）を返す。
     *
     * <p>自立移行 UX（02_api_design §2.3「境界日もポリシーが返す」）で、保護者へ
     * 「◯月からお子さまが自立します」と予告するために用いる。境界日は国別ルールで異なる:</p>
     * <ul>
     *   <li>JP（{@link JapanGuardianshipAgePolicy}）: 満12歳に達する年度の<b>翌年度4/1</b>（年度替わりで封印）。</li>
     *   <li>フォールバック（{@link DefaultGuardianshipAgePolicy}）: <b>満13歳の誕生日</b>（誕生日基準で封印）。</li>
     * </ul>
     *
     * <p>境界日は「封印が発火する日」そのもの（境界日当日に {@code switchAllowed=false}）。
     * 既に封印段階を過ぎている子に対しても、過去日となる境界日（既に発火済みの日）を返す
     * （実装は {@link #resolve} と同じ年度／誕生日計算に基づき、現在時刻に依らず生年月日から一意に定まる）。</p>
     *
     * @param birthDate 子の生年月日（復号済み・null 不可）
     * @param clock     基準時計（年度評価のタイムゾーン解決に使う実装がある・テスト時は {@link Clock#fixed}）
     * @return 切替が封印される境界日（その日以降 {@code switchAllowed=false}）
     */
    LocalDate sealDate(LocalDate birthDate, Clock clock);

    /**
     * このポリシーが対応する ISO 3166-1 alpha-2 国コード（例: {@code "JP"}）。
     * フォールバック実装は {@code null} を返してよい。
     *
     * @return 対応国コード（フォールバックは null）
     */
    String supportedCountryCode();
}
