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
  it('ALB-001: バッジが描画され「広告」文言が含まれる', async () => {
    const wrapper = await mountSuspended(AdLabelBadge)
    expect(wrapper.text()).toContain('広告')
  })

  it('ALB-002: ARIA role="region" + aria-label が付与される', async () => {
    const wrapper = await mountSuspended(AdLabelBadge)
    const span = wrapper.find('span[role="region"]')
    expect(span.exists()).toBe(true)
    expect(span.attributes('aria-label')).toBe('広告')
  })

  it('ALB-003: バッジ色 #FF9800 が style に含まれる', async () => {
    const wrapper = await mountSuspended(AdLabelBadge)
    const html = wrapper.html()
    // toLowerCase で大文字小文字差を吸収
    expect(html.toLowerCase()).toContain('#ff9800')
  })
})
