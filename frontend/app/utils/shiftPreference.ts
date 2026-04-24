import type { ShiftPreference } from '~/types/shift'

/**
 * F03.5 シフト希望強度ユーティリティ。
 *
 * 5段階の ShiftPreference に対して i18n キー・Tailwind 色クラス・割当スコアを提供する。
 * 設計書§5 のラジオカード 5色仕様に準拠:
 *   ABSOLUTE_REST=赤, STRONG_REST=橙, WEAK_REST=黄, AVAILABLE=灰, PREFERRED=緑
 */

/**
 * ShiftPreference を i18n キーに変換する。
 * @param preference 希望強度
 * @returns i18n キー文字列（例: `'shift.preference.preferred'`）
 */
export function preferenceToI18nKey(preference: ShiftPreference): string {
  const map: Record<ShiftPreference, string> = {
    PREFERRED: 'shift.preference.preferred',
    AVAILABLE: 'shift.preference.available',
    WEAK_REST: 'shift.preference.weakRest',
    STRONG_REST: 'shift.preference.strongRest',
    ABSOLUTE_REST: 'shift.preference.absoluteRest',
  }
  return map[preference]
}

/**
 * ShiftPreference を Tailwind 背景色クラスに変換する。
 * ラジオカード UI（SelectButton）のチップ色として使用する。
 * @param preference 希望強度
 * @returns Tailwind CSS クラス文字列
 */
export function preferenceToColor(preference: ShiftPreference): string {
  const map: Record<ShiftPreference, string> = {
    PREFERRED: 'bg-green-100 text-green-800 border-green-300',
    AVAILABLE: 'bg-gray-100 text-gray-700 border-gray-300',
    WEAK_REST: 'bg-yellow-100 text-yellow-800 border-yellow-300',
    STRONG_REST: 'bg-orange-100 text-orange-800 border-orange-300',
    ABSOLUTE_REST: 'bg-red-100 text-red-800 border-red-300',
  }
  return map[preference]
}

/**
 * ShiftPreference を自動割当スコアに変換する。
 *
 * スコア定義（設計書 F03.5 v2.3 §5.10 準拠・Phase 1 MVP 実装値）:
 * | 値             | スコア            |
 * |---|---|
 * | PREFERRED      | +100 （出勤希望）  |
 * | AVAILABLE      | 0    （指定なし）   |
 * | WEAK_REST      | -30  （出れなくはない） |
 * | STRONG_REST    | -80  （できれば休み）   |
 * | ABSOLUTE_REST  | -Infinity（割当不可）  |
 *
 * 旧 v2.2 の +50/-20 から Phase 1 MVP で +100/-30 へ上方修正し、設計書側を v2.3 として追随。
 * バックエンドの {@code ShiftPreference#toAssignmentScore()} と一致。Phase 2 の自動割当実機
 * テスト結果を踏まえ、動的重み調整の余地を残す。
 * @param preference 希望強度
 * @returns 割当スコア（ABSOLUTE_REST は Number.NEGATIVE_INFINITY）
 */
export function preferenceToScore(preference: ShiftPreference): number {
  const map: Record<ShiftPreference, number> = {
    PREFERRED: 100,
    AVAILABLE: 0,
    WEAK_REST: -30,
    STRONG_REST: -80,
    ABSOLUTE_REST: Number.NEGATIVE_INFINITY,
  }
  return map[preference]
}
