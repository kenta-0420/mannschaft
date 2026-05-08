import { describe, it, expect } from 'vitest'
import CardEditorCommonFields from '~/components/corkboard/card-editor/CardEditorCommonFields.vue'
import { mountWithContext } from './_helpers'

/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorCommonFields のユニットテスト。
 *
 * テストケース:
 *  CECF-001: 基本レンダリング — cardType セレクタ・カラーラベルラジオ・位置 InputNumber が描画される
 *  CECF-002: create モード — cardType セレクタ (testid=card-editor-card-type-select) が表示される
 *  CECF-003: edit モード — cardType セレクタは表示されず、固定表示の span に切替わる
 *  CECF-004: カラーラベル radiogroup — 7 色分のボタンが描画される
 *  CECF-005: 色クリックで context.colorLabel が更新される
 *  CECF-006: position fieldset の legend が描画される
 */

describe('CardEditorCommonFields.vue', () => {
  it('CECF-001: 基本レンダリング — カラーラベルと位置 fieldset が描画される', async () => {
    const { wrapper } = await mountWithContext(CardEditorCommonFields)
    // カラーラベル radiogroup
    expect(wrapper.find('[role="radiogroup"]').exists()).toBe(true)
    // position fieldset
    expect(wrapper.find('fieldset').exists()).toBe(true)
  })

  it('CECF-002: create モードでは cardType セレクタが表示される', async () => {
    const { wrapper } = await mountWithContext(CardEditorCommonFields, {
      mode: 'create',
    })
    expect(
      wrapper.find('[data-testid="card-editor-card-type-select"]').exists(),
    ).toBe(true)
  })

  it('CECF-003: edit モードでは cardType は固定表示（セレクタは非表示）', async () => {
    const { wrapper } = await mountWithContext(CardEditorCommonFields, {
      mode: 'edit',
    })
    expect(
      wrapper.find('[data-testid="card-editor-card-type-select"]').exists(),
    ).toBe(false)
    // 固定表示の span（カード種別ラベル）が描画されている
    expect(wrapper.find('span.inline-flex').exists()).toBe(true)
  })

  it('CECF-004: カラーラベル radiogroup には 7 色分のボタンが描画される', async () => {
    const { wrapper } = await mountWithContext(CardEditorCommonFields)
    const buttons = wrapper.findAll('[role="radio"]')
    expect(buttons.length).toBe(7)
  })

  it('CECF-005: 色クリックで context.colorLabel が更新される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorCommonFields)
    expect(form.colorLabel.value).toBe('WHITE')
    const redBtn = wrapper.find('[data-testid="card-editor-color-label-RED"]')
    expect(redBtn.exists()).toBe(true)
    await redBtn.trigger('click')
    expect(form.colorLabel.value).toBe('RED')
  })

  it('CECF-006: position fieldset の legend が描画される', async () => {
    const { wrapper } = await mountWithContext(CardEditorCommonFields)
    const legend = wrapper.find('fieldset > legend')
    expect(legend.exists()).toBe(true)
    // legend のテキストが空でないこと（i18n の実値またはキーが入る）
    expect(legend.text().length).toBeGreaterThan(0)
  })
})
