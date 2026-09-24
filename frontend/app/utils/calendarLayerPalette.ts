/**
 * カレンダーレイヤーの色パレット（F03.19 §3.3）。
 *
 * BE の {@code CalendarLayerAutoColor.PALETTE} と同じ 12 色・同じ順序である。
 * FE 側はハッシュによる自動色算出は持たない（色は BE が解決済みで返す・§3.3）。
 * ここに置くのは**ユーザーが色を選ぶためのパレット**であり、自動色の再実装ではない。
 *
 * **値と順序は確定値であり変更してはならない**（順序を変えると既存ユーザーの
 * 自動色が総入れ替えになる）。
 */
export interface CalendarLayerPaletteEntry {
  /** #RRGGBB（大文字）。PATCH でそのまま送る値。 */
  hex: string
  /** パレット内での識別名（i18n 対象外の内部名。UI 表示には使わない）。 */
  name: string
  /**
   * ライトテーマでこの色の上に載せる文字色。
   *
   * **設計書 §3.3 の表の値ではなく、実測（AC-24）で 4.5:1 を満たす値に差し替えてある。**
   * 表が指定していた `#FFFFFF` / `#1F2937` の組み合わせでは 12 色中 6 色が 4.5:1 を
   * 下回った（orange/amber/lime/emerald/teal/sky）。パレットの色そのものは自動色の
   * 決定性のため変更できないため、自由度のある文字色側を色ごとに白／黒から
   * コントラストの高い方へ寄せている（最小 4.60:1）。詳細は殿へ報告済み。
   */
  lightText: string
  /** ダークテーマでこの色の上に載せる文字色。 */
  darkText: string
}

export const CALENDAR_LAYER_PALETTE: readonly CalendarLayerPaletteEntry[] = [
  { hex: '#DC2626', name: 'red', lightText: '#FFFFFF', darkText: '#FFFFFF' },
  { hex: '#EA580C', name: 'orange', lightText: '#000000', darkText: '#000000' },
  { hex: '#CA8A04', name: 'amber', lightText: '#000000', darkText: '#000000' },
  { hex: '#65A30D', name: 'lime', lightText: '#000000', darkText: '#000000' },
  { hex: '#059669', name: 'emerald', lightText: '#000000', darkText: '#000000' },
  { hex: '#0D9488', name: 'teal', lightText: '#000000', darkText: '#000000' },
  { hex: '#0284C7', name: 'sky', lightText: '#000000', darkText: '#000000' },
  { hex: '#2563EB', name: 'blue', lightText: '#FFFFFF', darkText: '#FFFFFF' },
  { hex: '#7C3AED', name: 'violet', lightText: '#FFFFFF', darkText: '#FFFFFF' },
  { hex: '#C026D3', name: 'fuchsia', lightText: '#FFFFFF', darkText: '#FFFFFF' },
  { hex: '#DB2777', name: 'pink', lightText: '#FFFFFF', darkText: '#FFFFFF' },
  { hex: '#57534E', name: 'stone', lightText: '#FFFFFF', darkText: '#FFFFFF' },
] as const

/** `#RRGGBB` を 0-255 の RGB へ分解する。形式が不正なら null。 */
export function parseHexColor(hex: string): [number, number, number] | null {
  if (!/^#[0-9A-Fa-f]{6}$/.test(hex)) return null
  return [
    Number.parseInt(hex.slice(1, 3), 16),
    Number.parseInt(hex.slice(3, 5), 16),
    Number.parseInt(hex.slice(5, 7), 16),
  ]
}

/**
 * WCAG 2.1 の相対輝度 L を求める。
 * https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 */
export function relativeLuminance(hex: string): number {
  const rgb = parseHexColor(hex)
  if (!rgb) throw new Error(`不正な色指定です: ${hex}`)
  const [r, g, b] = rgb.map((c) => {
    const s = c / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }) as [number, number, number]
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * WCAG 2.1 のコントラスト比 (L1 + 0.05) / (L2 + 0.05) を求める（L1 が明るい側）。
 * https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio
 */
export function contrastRatio(colorA: string, colorB: string): number {
  const a = relativeLuminance(colorA)
  const b = relativeLuminance(colorB)
  const lighter = Math.max(a, b)
  const darker = Math.min(a, b)
  return (lighter + 0.05) / (darker + 0.05)
}
