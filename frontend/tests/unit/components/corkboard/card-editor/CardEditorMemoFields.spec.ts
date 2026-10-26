import { describe, it, expect } from 'vitest'
import CardEditorMemoFields from '~/components/corkboard/card-editor/CardEditorMemoFields.vue'
import { mountWithContext } from './_helpers'

/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorMemoFields のユニットテスト。
 *
 * テストケース:
 *  CEMF-001: 基本レンダリング — title / body / userNote の入力欄が描画される
 *  CEMF-002: context.body が反映される（v-model 双方向）
 *  CEMF-003: errors.body 設定時にエラーメッセージが表示される
 *  CEMF-004: errors.body 未設定時はエラー要素が描画されない
 */

describe('CardEditorMemoFields.vue', () => {
  it('CEMF-001: 基本レンダリング — title/body/userNote 入力が描画される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorMemoFields)
    // cardType を MEMO に
    form.cardType.value = 'MEMO'
    expect(
      wrapper.find('[data-testid="card-editor-title-input"]').exists(),
    ).toBe(true)
    expect(
      wrapper.find('[data-testid="card-editor-body-input"]').exists(),
    ).toBe(true)
  })

  it('CEMF-002: context.body の値が body 入力欄に反映される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorMemoFields)
    form.body.value = 'メモ本文テスト'
    await wrapper.vm.$nextTick()
    const textarea = wrapper.find<HTMLTextAreaElement>(
      '[data-testid="card-editor-body-input"]',
    )
    expect((textarea.element as HTMLTextAreaElement).value).toBe('メモ本文テスト')
  })

  it('CEMF-003: errors.body 設定時にエラーメッセージが表示される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorMemoFields)
    form.errors.value = { body: 'memo body required' }
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('memo body required')
  })

  it('CEMF-004: errors.body 未設定時はエラー <small> が描画されない', async () => {
    const { wrapper } = await mountWithContext(CardEditorMemoFields)
    // errors.body が未設定
    const reds = wrapper.findAll('small.text-red-500')
    expect(reds.length).toBe(0)
  })
})
