/** ランディングページの年代別テーマID */
export type EraTheme =
  | 'modern'
  | 'y1998'
  | 'y2000'
  | 'y2005'
  | 'y2010'
  | 'y2015'
  | 'y2020'
  | 'fc'
  | 'sfc'

/** 全テーマ（modern含む）のIDリスト */
export const ALL_ERA_THEMES = [
  'modern',
  'y1998',
  'y2000',
  'y2005',
  'y2010',
  'y2015',
  'y2020',
  'fc',
  'sfc',
] as const satisfies readonly EraTheme[]

/** レトロテーマ（modernを除く8種）のIDリスト */
export const RETRO_ERA_THEMES = [
  'y1998',
  'y2000',
  'y2005',
  'y2010',
  'y2015',
  'y2020',
  'fc',
  'sfc',
] as const satisfies readonly EraTheme[]

/** モバイルで非表示にするゲーム機テーマ（Q2: 案B） */
export const GAME_CONSOLE_THEMES = ['fc', 'sfc'] as const satisfies readonly EraTheme[]

/**
 * 値が有効なEraThemeかチェックする型ガード関数。
 * URLクエリ等の外部入力をホワイトリスト検証するために使用する。
 * プロトタイプ汚染（"constructor"等）も自動的にfalseを返す。
 */
export function isEraTheme(value: unknown): value is EraTheme {
  if (typeof value !== 'string') return false
  return (ALL_ERA_THEMES as readonly string[]).includes(value)
}
