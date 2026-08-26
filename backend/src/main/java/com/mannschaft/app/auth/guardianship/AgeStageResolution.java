package com.mannschaft.app.auth.guardianship;

/**
 * F08.9 P3a 後見切替の年齢段階判定結果。
 *
 * <p>{@link GuardianshipAgePolicy#resolve} が返す不変値。生年月日そのものは持ち回らず、
 * 「切替可否」と「段階の i18n キー」のみを扱う（03_security §3.1：判定は復号値で行い結果のみ保持）。</p>
 *
 * @param switchAllowed 後見切替が許可される段階なら {@code true}（封印段階＝中学生以降等で {@code false}）
 * @param stageKey      段階を表す i18n ラベルキー（国依存。日本＝{@code elementary} / {@code junior_high}、
 *                      未対応国フォールバック＝{@code minor} / {@code independent}）。UI 直書き禁止のため
 *                      フロント側で i18n ラベルへ解決する
 */
public record AgeStageResolution(boolean switchAllowed, String stageKey) {
}
