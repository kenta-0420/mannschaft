// @vitest-environment happy-dom
// この worktree では setupNuxt が完走しない既知の環境問題があり（他の spec も同じ対処）、
// Nuxt 環境を必要としないこのテストは happy-dom で素に動かす。
import { describe, expect, it } from 'vitest'
import { createJaLocaleT } from './localeJson'

/**
 * localeJson ヘルパー自体のユニットテスト。
 *
 * この worktree では `mountSuspended`（setupNuxt）が完走しない既知の環境問題があり、
 * 3ファイル（SystemAdminPriceRevisionsNav.spec.ts / price-revisions-jikki-detail.spec.ts /
 * price-revisions-jikki-taxcode.spec.ts）を実行して赤を実測できない。そのため、
 * これらが依存する `createJaLocaleT` 自体の「キーが locale.json から消えたら throw する」
 * 挙動を、Nuxt に依存しないこのテストで直接検証する。
 */
describe('createJaLocaleT', () => {
  it('実在するキーを common.json から解決する', () => {
    const t = createJaLocaleT(['common'])
    expect(t('admin.quickLinks.priceRevisions')).toBe('価格改定')
  })

  it('実在するキーを billing.json から解決する', () => {
    const t = createJaLocaleT(['billing'])
    expect(t('billing.priceRevisions.noPermission')).toBe('この画面を表示する権限がありません')
  })

  it('存在しないキーは throw する（key をそのまま返さない）', () => {
    const t = createJaLocaleT(['billing'])
    expect(() => t('billing.priceRevisions.doesNotExist')).toThrow(/locale key not found/)
  })
})
