import { describe, it, expect } from 'vitest'
import CardEditorSectionHeaderFields from '~/components/corkboard/card-editor/CardEditorSectionHeaderFields.vue'
import { mountWithContext } from './_helpers'

/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorSectionHeaderFields のユニットテスト。
 *
 * テストケース:
 *  CESF-001: 基本レンダリング — title 入力欄が描画される
 *  CESF-002: context.title が title 入力欄に反映される
 *  CESF-003: errors.title 設定時にエラーメッセージが表示される
 */

describe('CardEditorSectionHeaderFields.vue', () => {
  it('CESF-001: 基本レンダリング — title 入力が描画される', async () => {
    const { wrapper } = await mountWithContext(CardEditorSectionHeaderFields)
    expect(
      wrapper.find('[data-testid="card-editor-title-input"]').exists(),
    ).toBe(true)
  })

  it('CESF-002: context.title の値が title 入力欄に反映される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorSectionHeaderFields)
    form.title.value = 'セクション見出し'
    await wrapper.vm.$nextTick()
    const input = wrapper.find<HTMLInputElement>(
      '[data-testid="card-editor-title-input"]',
    )
    expect((input.element as HTMLInputElement).value).toBe('セクション見出し')
  })

  it('CESF-003: errors.title 設定時にエラーメッセージが表示される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorSectionHeaderFields)
    form.errors.value = { title: 'section title required' }
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('section title required')
  })
})
