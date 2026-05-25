import { describe, it, expect } from 'vitest'
import CardEditorReferenceFields from '~/components/corkboard/card-editor/CardEditorReferenceFields.vue'
import { makeCardDetail, mountWithContext } from './_helpers'

/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorReferenceFields のユニットテスト。
 *
 * テストケース:
 *  CERF-001: 基本レンダリング — referenceType セレクタ・referenceId 入力が描画される
 *  CERF-002: create モード — referenceType / referenceId は disabled でない
 *  CERF-003: edit モード — referenceType / referenceId が disabled になる
 *  CERF-004: errors.referenceId 設定時にエラーメッセージが表示される
 *  CERF-005: URL ペースト成功 — referenceId が更新され success メッセージが出る
 *  CERF-006: URL ペースト失敗 — referenceId は変わらず error メッセージが出る
 *  CERF-007: edit モード（または referenceType=URL）では URL ペースト補助欄は描画されない
 */

describe('CardEditorReferenceFields.vue', () => {
  it('CERF-001: 基本レンダリング — refType セレクタ + refId 入力が描画される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorReferenceFields, {
      mode: 'create',
    })
    form.cardType.value = 'REFERENCE'
    expect(
      wrapper.find('[data-testid="card-editor-reference-type-select"]').exists(),
    ).toBe(true)
    expect(
      wrapper.find('[data-testid="card-editor-reference-id-input"]').exists(),
    ).toBe(true)
  })

  it('CERF-002: create モードでは refType / refId は disabled でない', async () => {
    const { wrapper } = await mountWithContext(CardEditorReferenceFields, {
      mode: 'create',
    })
    // refId 入力（PrimeVue InputNumber は input 要素を生成）
    const refIdInputWrapper = wrapper.find(
      '[data-testid="card-editor-reference-id-input"]',
    )
    const inputEl = refIdInputWrapper.find('input')
    expect(inputEl.exists()).toBe(true)
    expect((inputEl.element as HTMLInputElement).disabled).toBe(false)
  })

  it('CERF-003: edit モードでは refType / refId が disabled になる', async () => {
    const card = makeCardDetail({
      reference: {
        cardType: 'REFERENCE',
        referenceType: 'TIMELINE_POST',
        referenceId: 42,
        sectionId: null,
        contentSnapshot: null,
      },
    })
    const { wrapper } = await mountWithContext(CardEditorReferenceFields, {
      mode: 'edit',
      card,
    })
    const refIdInputWrapper = wrapper.find(
      '[data-testid="card-editor-reference-id-input"]',
    )
    const inputEl = refIdInputWrapper.find('input')
    expect(inputEl.exists()).toBe(true)
    expect((inputEl.element as HTMLInputElement).disabled).toBe(true)
  })

  it('CERF-004: errors.referenceId 設定時にエラーメッセージが表示される', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorReferenceFields, {
      mode: 'create',
    })
    form.errors.value = { referenceId: 'reference id required' }
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('reference id required')
  })

  it('CERF-005: URL ペースト成功 — referenceId が更新され success メッセージが出る', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorReferenceFields, {
      mode: 'create',
    })
    form.cardType.value = 'REFERENCE'
    form.referenceType.value = 'TIMELINE_POST'
    form.referenceUrlPaste.value = 'https://app.example.com/timeline/posts/777'
    await wrapper.vm.$nextTick()

    const btn = wrapper.find(
      '[data-testid="card-editor-reference-url-paste-button"]',
    )
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    await wrapper.vm.$nextTick()

    expect(form.referenceId.value).toBe(777)
    expect(form.referenceUrlPasteMessage.value?.kind).toBe('success')
    const msg = wrapper.find(
      '[data-testid="card-editor-reference-url-paste-message"]',
    )
    expect(msg.exists()).toBe(true)
  })

  it('CERF-006: URL ペースト失敗 — referenceId は変わらず error メッセージが出る', async () => {
    const { wrapper, form } = await mountWithContext(CardEditorReferenceFields, {
      mode: 'create',
    })
    form.cardType.value = 'REFERENCE'
    form.referenceType.value = 'TIMELINE_POST'
    // 数字を含まない URL
    form.referenceUrlPaste.value = 'https://app.example.com/no-numbers-here/'
    await wrapper.vm.$nextTick()

    const btn = wrapper.find(
      '[data-testid="card-editor-reference-url-paste-button"]',
    )
    await btn.trigger('click')
    await wrapper.vm.$nextTick()

    expect(form.referenceId.value).toBeNull()
    expect(form.referenceUrlPasteMessage.value?.kind).toBe('error')
  })

  it('CERF-007: edit モードでは URL ペースト補助欄が描画されない', async () => {
    const card = makeCardDetail({
      reference: {
        cardType: 'REFERENCE',
        referenceType: 'TIMELINE_POST',
        referenceId: 1,
        sectionId: null,
        contentSnapshot: null,
      },
    })
    const { wrapper } = await mountWithContext(CardEditorReferenceFields, {
      mode: 'edit',
      card,
    })
    expect(
      wrapper.find('[data-testid="card-editor-reference-url-paste-button"]').exists(),
    ).toBe(false)
  })
})
