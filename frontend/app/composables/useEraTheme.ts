import type { LocationQueryValue } from 'vue-router'
import { RETRO_ERA_THEMES, GAME_CONSOLE_THEMES, isEraTheme } from '~/types/eraTheme'
import type { EraTheme } from '~/types/eraTheme'

/** クローラのUser-Agent正規表現（SEO保護のためモダン固定） */
const CRAWLER_RE =
  /Googlebot|Bingbot|Slurp|DuckDuckBot|facebot|ia_archiver|Twitterbot|Lighthousebot|Chrome-Lighthouse/i

/** モバイルUser-Agent正規表現（ゲーム機テーマ非対応のため除外） */
const MOBILE_RE = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i

/** クローラかどうか判定（ユニットテスト用にエクスポート） */
export function isCrawler(ua: string): boolean {
  return CRAWLER_RE.test(ua)
}

/** モバイルデバイスかどうか判定（ユニットテスト用にエクスポート） */
export function isMobile(ua: string): boolean {
  return MOBILE_RE.test(ua)
}

/**
 * 年代別テーマを選択するcomposable。
 *
 * pickTheme()の優先度（設計書 §5.2）:
 * 1. SSR中は常に'modern'（ハイドレーション不一致防止）
 * 2. クローラUA → 'modern'固定（SEO死守・クエリより優先）
 * 3. ?era=クエリに有効テーマIDがあれば強制適用
 * 4. 確率抽選: Math.random() < 0.01 でレトロテーマをランダム選択
 */
export function useEraTheme() {
  const route = useRoute()

  function pickTheme(): EraTheme {
    // 1. SSR中は常にmodern（ClientOnly内で呼ばれるため通常は到達しない）
    if (import.meta.server) return 'modern'

    const ua = navigator.userAgent

    // 2. クローラはmodern固定（クエリ指定より優先）
    if (isCrawler(ua)) return 'modern'

    // 3. ?era=クエリで強制指定
    const rawQuery: LocationQueryValue | LocationQueryValue[] = route.query.era
    const eraQuery: LocationQueryValue = Array.isArray(rawQuery) ? rawQuery[0] : rawQuery
    if (isEraTheme(eraQuery)) {
      // モバイルでゲーム機テーマが指定された場合はmodernにフォールバック（Q2: 案B）
      if (isMobile(ua) && (GAME_CONSOLE_THEMES as readonly string[]).includes(eraQuery)) {
        return 'modern'
      }
      return eraQuery
    }

    // 4. 確率抽選: 全体の1/100でレトロテーマを発動（1テーマあたり1/800）
    if (Math.random() < 0.01) {
      const idx = Math.floor(Math.random() * RETRO_ERA_THEMES.length)
      const picked = RETRO_ERA_THEMES[idx] as EraTheme
      // モバイルでゲーム機テーマが当選した場合はmodernにフォールバック（Q2: 案B）
      if (isMobile(ua) && (GAME_CONSOLE_THEMES as readonly string[]).includes(picked)) {
        return 'modern'
      }
      // 開発環境のみコンソールログ（本番ビルドでtree-shake削除）
      if (import.meta.dev) {
        console.log(`🎉 You found a "${picked}" easter egg!`)
      }
      return picked
    }

    return 'modern'
  }

  return { pickTheme }
}
