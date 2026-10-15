/**
 * F08.10 分析チャート用 色覚多様性パレット（04_frontend_and_ux.md §G.12）。
 *
 * Okabe-Ito の色盲フレンドリーパレット（8 色）を採用する。
 * 1 型・2 型・3 型色覚いずれでも識別しやすい配色として広く使われる定番。
 * チャートは色だけに依存せず、凡例ラベル・データラベルを併用する方針（§G.12）。
 *
 * @see https://jfly.uni-koeln.de/color/ — Okabe & Ito, Color Universal Design
 */

/** 不透明な系列色（線・点・枠線） */
export const OKABE_ITO = {
  /** 黒（基準・テキスト寄り） */
  black: '#000000',
  /** オレンジ */
  orange: '#E69F00',
  /** スカイブルー */
  skyBlue: '#56B4E9',
  /** 緑（黄緑寄り） */
  bluishGreen: '#009E73',
  /** 黄 */
  yellow: '#F0E442',
  /** 青 */
  blue: '#0072B2',
  /** 朱（バーミリオン） */
  vermillion: '#D55E00',
  /** 赤紫 */
  reddishPurple: '#CC79A7',
} as const

/**
 * 系列の既定パレット（複数 dataset / doughnut のセグメントに順番で割り当てる）。
 * 黒は背景に埋もれやすいので末尾に回し、視認性の高い色から並べる。
 */
export const CHART_PALETTE: readonly string[] = [
  OKABE_ITO.blue,
  OKABE_ITO.vermillion,
  OKABE_ITO.bluishGreen,
  OKABE_ITO.orange,
  OKABE_ITO.skyBlue,
  OKABE_ITO.reddishPurple,
  OKABE_ITO.yellow,
  OKABE_ITO.black,
] as const

/**
 * 16 進カラーに不透明度を付与して rgba 文字列を返す（塗りつぶし用）。
 * @param hex `#RRGGBB` 形式の 16 進カラー
 * @param alpha 0〜1 の不透明度
 */
export function withAlpha(hex: string, alpha: number): string {
  const m = /^#?([0-9a-fA-F]{6})$/.exec(hex.trim())
  if (!m) return hex
  const int = parseInt(m[1]!, 16)
  const r = (int >> 16) & 0xff
  const g = (int >> 8) & 0xff
  const b = int & 0xff
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/**
 * インデックスに対応するパレット色を循環で返す。
 * @param index 系列・セグメントのインデックス
 */
export function paletteColor(index: number): string {
  return CHART_PALETTE[index % CHART_PALETTE.length]!
}
