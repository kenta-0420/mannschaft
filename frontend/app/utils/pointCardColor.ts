/**
 * F18 ポイントカードウォレット — カラーユーティリティ。
 *
 * 設計書: docs/features/F18_point_card_wallet.md §8.5
 *
 * <p>プロバイダーの `brand_color` に対する WCAG AA コントラスト判定と、
 * プロバイダー未マッチカード用の頭文字アイコン背景色の決定論的生成を提供する。</p>
 */

/**
 * 背景色 (#RRGGBB) に対して WCAG AA コントラストを満たす文字色（白 or 黒）を返す。
 *
 * <p>YIQ 輝度 (0.299R + 0.587G + 0.114B) を 0..1 に正規化し、0.5 を閾値に切り替える。
 * これは厳密な WCAG 4.5:1 比較ではないが、Phase 1 のブランドカラーパレット範囲では
 * 視覚的に十分なコントラストが確保できる軽量な判定式。設計書 §8.5 で許容。</p>
 *
 * @param hex `#RRGGBB` または `RRGGBB` 形式の色。null/undefined/不正値の場合は黒を返す
 */
export function getContrastColor(hex: string | null | undefined): '#000000' | '#FFFFFF' {
  if (!hex) return '#000000'

  const normalized = hex.startsWith('#') ? hex.slice(1) : hex
  if (normalized.length !== 6 || !/^[0-9a-fA-F]{6}$/.test(normalized)) {
    return '#000000'
  }

  const r = parseInt(normalized.substring(0, 2), 16)
  const g = parseInt(normalized.substring(2, 4), 16)
  const b = parseInt(normalized.substring(4, 6), 16)
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
  return luminance > 0.5 ? '#000000' : '#FFFFFF'
}

/**
 * 文字列から決定論的に HSL 色を生成する（プロバイダー未マッチカードの頭文字アイコン背景用）。
 *
 * <p>同じ入力 `name` は常に同じ色を返す（djb2 風の単純ハッシュ）。
 * 彩度 60% / 明度 50% に固定し、`getContrastColor` 後段の白黒判定で
 * WCAG AA を満たす文字色を選択できるよう調整している。</p>
 *
 * @param name 任意の文字列（通常は `displayName`）
 * @returns `hsl(H, 60%, 50%)` 形式の CSS カラー文字列
 */
export function getInitialAvatarColor(name: string): string {
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash)
  }
  const hue = Math.abs(hash) % 360
  return `hsl(${hue}, 60%, 50%)`
}

/**
 * `displayName` の先頭 1 文字を頭文字アイコン用に取得する。
 * サロゲートペア（絵文字等）を考慮し Array.from で正しく 1 グラフェムを取り出す。
 * 空文字や undefined の場合は `?` を返す。
 */
export function getInitialChar(name: string | null | undefined): string {
  if (!name) return '?'
  const trimmed = name.trim()
  if (!trimmed) return '?'
  return Array.from(trimmed)[0] ?? '?'
}
