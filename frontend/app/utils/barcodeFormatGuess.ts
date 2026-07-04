/**
 * F18 ウォレット — バーコード形式自動推測ユーティリティ。
 *
 * <p>手入力タブでバーコード値の入力が変化したとき、チェックディジットを検証した上で
 * 最適な {@link BarcodeFormat} を推測する純粋関数群を提供する。</p>
 *
 * <p>桁数だけで形式を決める実装は「任意の13桁 → EAN13」と推測してしまい、
 * JsBarcode が例外を投げて描画失敗エラーを引き起こす（バグ根治の経緯）。
 * GS1 規格のチェックディジット検証を行うことで、無効な EAN13/EAN8 を
 * CODE128 に安全にフォールバックさせ、描画失敗を原理的になくす。</p>
 *
 * @see BarcodeFormat ~/types/pointCard
 */
import type { BarcodeFormat } from '~/types/pointCard'

/**
 * 13 桁数字文字列が GS1 EAN-13 のチェックディジット要件を満たすか検証する。
 *
 * <p>GS1 アルゴリズム: 左から d1..d12 に対し、奇数インデックス（0-based: 0,2,...,10）には
 * ×1、偶数インデックス（0-based: 1,3,...,11）には ×3 を乗じた合計を計算し、
 * `(10 - (sum % 10)) % 10` が d13 と一致すれば有効。</p>
 *
 * @param value 検証対象の文字列（13桁数字であることが前提）
 * @returns チェックディジットが正しい場合 true
 */
export function isValidEan13(value: string): boolean {
  if (!/^\d{13}$/.test(value)) return false
  const digits = value.split('').map(Number)
  let sum = 0
  for (let i = 0; i < 12; i++) {
    // GS1 EAN-13: 奇数位置（0-based偶数インデックス）×1、偶数位置（0-based奇数インデックス）×3
    sum += digits[i] * (i % 2 === 0 ? 1 : 3)
  }
  const checkDigit = (10 - (sum % 10)) % 10
  return checkDigit === digits[12]
}

/**
 * 8 桁数字文字列が GS1 EAN-8 のチェックディジット要件を満たすか検証する。
 *
 * <p>GS1 アルゴリズム: 左から d1..d7 に対し、奇数インデックス（0-based: 0,2,4,6）には
 * ×3、偶数インデックス（0-based: 1,3,5）には ×1 を乗じた合計を計算し、
 * `(10 - (sum % 10)) % 10` が d8 と一致すれば有効。</p>
 *
 * @param value 検証対象の文字列（8桁数字であることが前提）
 * @returns チェックディジットが正しい場合 true
 */
export function isValidEan8(value: string): boolean {
  if (!/^\d{8}$/.test(value)) return false
  const digits = value.split('').map(Number)
  let sum = 0
  for (let i = 0; i < 7; i++) {
    // GS1 EAN-8: 奇数位置（0-based偶数インデックス）×3、偶数位置（0-based奇数インデックス）×1
    sum += digits[i] * (i % 2 === 0 ? 3 : 1)
  }
  const checkDigit = (10 - (sum % 10)) % 10
  return checkDigit === digits[7]
}

/**
 * バーコード値の文字列から最適な {@link BarcodeFormat} を推測して返す純粋関数。
 *
 * <p>チェックディジット検証により描画不能な形式を選ばない設計。
 * GS1 チェックディジットが正しい場合のみ EAN13/EAN8 を選択し、
 * それ以外はすべて CODE128 に安全フォールバックする。</p>
 *
 * <p>選択ロジック:</p>
 * <ol>
 *   <li>13 桁数字 かつ EAN-13 チェックディジット正常 → {@code EAN13}</li>
 *   <li>8 桁数字 かつ EAN-8 チェックディジット正常 → {@code EAN8}</li>
 *   <li>それ以外（英数字混在・桁数不一致・検査数字不一致・空文字列 など）→ {@code CODE128}</li>
 * </ol>
 *
 * <p>CODE128 は任意のテキスト・数字を描画可能で、チェックディジット制約なし。
 * 会員カードの任意桁番号（例: 13 桁の会員番号だが EAN-13 非準拠）でも必ず描画できる。</p>
 *
 * @param value バーコードの生値（trim 済みを想定）
 * @returns 推測された BarcodeFormat
 */
export function guessBarcodeFormat(value: string): BarcodeFormat {
  if (isValidEan13(value)) return 'EAN13'
  if (isValidEan8(value)) return 'EAN8'
  return 'CODE128'
}
