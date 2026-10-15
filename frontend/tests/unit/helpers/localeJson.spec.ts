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

  /**
   * CI の FE Install ジョブで実測された赤（nuxt.config.ts の files 登録全体をマージせず、
   * 呼び出し側が渡した namespace だけしか読んでいなかったための偽陰性）の再現・回帰防止。
   * SystemAdminPriceRevisionsNav.spec.ts / price-revisions-jikki-taxcode.spec.ts が
   * 実際に使うキーを直接確認する。
   */
  it('namespaces を渡さなくても common.json 以外のファイル（advertising.json）のキーを解決する', () => {
    const t = createJaLocaleT()
    expect(t('advertising.pages.system_admin_dashboard.quick_link_label')).toBe('広告審査')
  })

  it('button.search（common.json）を解決する', () => {
    const t = createJaLocaleT()
    expect(t('button.search')).toBe('検索')
  })

  it('common.detail（common.json の common ブロック）を解決する', () => {
    const t = createJaLocaleT()
    expect(t('common.detail')).toBe('詳細')
  })

  it('複数ファイルが共有するトップレベルキー（systemAdmin）を深くマージして解決する（浅い Object.assign の回帰防止）', () => {
    // common.json の systemAdmin.logs.quickLink と、system_admin_batch.json 等の別の
    // systemAdmin.xxx が同じトップレベルキーを共有する。浅いマージだと後から読んだファイルが
    // systemAdmin オブジェクト全体を上書きし、このキーが消える。
    const t = createJaLocaleT()
    expect(t('systemAdmin.logs.quickLink')).toBe('システムログ')
  })

  it('namespaces で渡していない namespace（jikki-detail.spec.ts が使う billing 以外）のキーも解決する', () => {
    const t = createJaLocaleT(['common'])
    expect(t('billing.priceRevisions.detailTitle')).toBeTypeOf('string')
  })

  it('nuxt.config.ts に登録されていないファイルのキーは throw する（登録漏れ検出を維持）', () => {
    const t = createJaLocaleT()
    expect(() => t('billing.priceRevisions.doesNotExist')).toThrow(/locale key not found/)
  })
})
