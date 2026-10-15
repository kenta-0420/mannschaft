import { describe, it, expect } from 'vitest'
import CardEditorUrlFields from '~/components/corkboard/card-editor/CardEditorUrlFields.vue'
import { mountWithContext } from './_helpers'

/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorUrlFields のユニットテスト。
 *
 * テストケース:
 *  CEUF-001: 基本レンダリング — url / title / userNote の入力欄が描画される
 *  CEUF-002: context.url が url 入力欄に反映される
 *  CEUF-003: errors.url 設定時にエラーメッセージが表示される
 */

describe('CardEditorUrlFields.vue', () => {
  it('CEUF-001: 基本レンダリング — url/title 入力が描画される', async () => {
    const { wrapper } = await mountWithContext(CardEditorUrlFields)
    expect(
      wrapper.find('[data-testid="card-editor-url-input"]').exists(),
    ).toBe(true)
    expect(
      wrapper.find('[data-testid="card-editor-title-input"]').exists(),
    ).toBe(true)
  })

  it('CEUF-002: context.url の値が url 入力欄に反映される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorUrlFields)
    form.url.value = 'https://example.com/'
    await wrapper.vm.$nextTick()
    const input = wrapper.find<HTMLInputElement>(
      '[data-testid="card-editor-url-input"]',
    )
    expect((input.element as HTMLInputElement).value).toBe('https://example.com/')
  })

  it('CEUF-003: errors.url 設定時にエラーメッセージが表示される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorUrlFields)
    form.errors.value = { url: 'invalid url' }
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('invalid url')
  })
})
