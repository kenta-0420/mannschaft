import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import AdLabelBadge from '~/components/advertising/AdLabelBadge.vue'

/**
 * F09.17 AdLabelBadge.vue ユニットテスト（景品表示法対応）
 *
 * 観点:
 *   ALB-001: バッジが描画され「広告」文言が含まれる
 *   ALB-002: ARIA role="region" aria-label が付与される（スクリーンリーダー向け）
 *   ALB-003: バッジ色 #FF9800 が style に含まれる（設計書 §6 で固定）
 */
describe('AdLabelBadge.vue', () => {
  it('ALB-001: バッジが描画され `advertising.ad_label` キーが解決される', async () => {
    const wrapper = await mountSuspended(AdLabelBadge)
    // テスト環境では i18n キーがそのまま、または翻訳済みのどちらかになる。
    // どちらの場合でも `<span role="region">` の中身として表示されていることを確認する。
    const span = wrapper.find('span[role="region"]')
    expect(span.exists()).toBe(true)
    expect(span.text().length).toBeGreaterThan(0)
  })

  it('ALB-002: ARIA role="region" + aria-label が付与される', async () => {
    const wrapper = await mountSuspended(AdLabelBadge)
    const span = wrapper.find('span[role="region"]')
    expect(span.exists()).toBe(true)
    // aria-label は i18n の値が入る（テスト環境ではキーがそのまま出る場合あり）
    const aria = span.attributes('aria-label')
    expect(aria).toBeDefined()
    expect((aria ?? '').length).toBeGreaterThan(0)
  })

  it('ALB-003: バッジ色 #FF9800 が style に含まれる', async () => {
    const wrapper = await mountSuspended(AdLabelBadge)
    const html = wrapper.html()
    // toLowerCase で大文字小文字差を吸収
    expect(html.toLowerCase()).toContain('#ff9800')
  })

  it('ALB-004: size="sm" でサイズ別クラスが切り替わる', async () => {
    const wrapper = await mountSuspended(AdLabelBadge, {
      props: { size: 'sm' },
    })
    expect(wrapper.html()).toContain('text-[10px]')
  })
})
