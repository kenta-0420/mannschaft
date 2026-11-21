// @vitest-environment happy-dom
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AllocationFormModal from './AllocationFormModal.vue'

/**
 * CMP-260910-1602: 予算割当ダイアログの保存失敗理由の表示と、必須項目のクライアント側検証。
 * 空のまま保存しても無反応だった（BE が 400 を返すのに画面に何も出ない）。
 */
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /><slot name="footer" /></div>' },
  InputNumber: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
  },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Textarea: true,
  Button: {
    props: ['label'],
    emits: ['click'],
    template: '<button :data-label="label" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function mountModal(props: Record<string, unknown> = {}) {
  return mount(AllocationFormModal, { props: { visible: true, ...props }, global: { stubs } })
}

async function clickSave(wrapper: ReturnType<typeof mountModal>) {
  await wrapper.find('button[data-label="shiftBudget.allocation.form.save"]').trigger('click')
  await flushPromises()
  await new Promise(resolve => setTimeout(resolve, 20))
  await flushPromises()
}

/** 作成モードの入力欄を埋める。順序: team / project / 年度 / 費目 / 開始日 / 終了日 / 金額 / 通貨 */
async function fill(wrapper: ReturnType<typeof mountModal>, values: { start?: string; end?: string }) {
  const inputs = wrapper.findAll('input')
  await inputs[2]!.setValue('1')
  await inputs[3]!.setValue('1')
  if (values.start !== undefined) await inputs[4]!.setValue(values.start)
  if (values.end !== undefined) await inputs[5]!.setValue(values.end)
}

describe('AllocationFormModal 保存失敗理由の表示', () => {
  it('formError があればダイアログ内にその理由を出す', async () => {
    const wrapper = mountModal({ formError: '同一スコープのシフト予算割当が既に存在します' })
    expect(wrapper.find('[data-testid="allocation-form-error"]').text()).toBe('同一スコープのシフト予算割当が既に存在します')
  })

  it('BE の fieldErrors(periodStart / periodEnd) を該当欄に出す', async () => {
    const wrapper = mountModal({ fieldErrors: { periodStart: '開始日は必須です', periodEnd: '終了日は必須です' } })
    expect(wrapper.find('[data-testid="allocation-error-period_start"]').text()).toBe('開始日は必須です')
    expect(wrapper.find('[data-testid="allocation-error-period_end"]').text()).toBe('終了日は必須です')
  })
})

describe('AllocationFormModal 必須項目の検証', () => {
  it('期間が未入力なら送信せず、開始日・終了日の欄にエラーを出す', async () => {
    const wrapper = mountModal()
    await fill(wrapper, {})
    await clickSave(wrapper)
    expect(wrapper.emitted('submit-create')).toBeUndefined()
    expect(wrapper.find('[data-testid="allocation-error-period_start"]').text()).toBe('shiftBudget.allocation.form.periodStartRequired')
    expect(wrapper.find('[data-testid="allocation-error-period_end"]').text()).toBe('shiftBudget.allocation.form.periodEndRequired')
  })

  it('終了日が開始日より前なら送信せず、終了日の欄にエラーを出す', async () => {
    const wrapper = mountModal()
    await fill(wrapper, { start: '2026-09-30', end: '2026-04-01' })
    await clickSave(wrapper)
    expect(wrapper.emitted('submit-create')).toBeUndefined()
    expect(wrapper.find('[data-testid="allocation-error-period_end"]').text()).toBe('shiftBudget.allocation.form.periodEndBeforeStart')
  })

  it('年度ID・費目IDが未選択(0)なら送信しない', async () => {
    const wrapper = mountModal()
    await clickSave(wrapper)
    expect(wrapper.emitted('submit-create')).toBeUndefined()
    expect(wrapper.find('[data-testid="allocation-error-fiscal_year_id"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="allocation-error-budget_category_id"]').exists()).toBe(true)
  })

  it('必須項目がそろっていれば送信する', async () => {
    const wrapper = mountModal()
    await fill(wrapper, { start: '2026-04-01', end: '2026-09-30' })
    await clickSave(wrapper)
    const emitted = wrapper.emitted('submit-create')
    expect(emitted).toHaveLength(1)
    expect(emitted![0]![0]).toMatchObject({ period_start: '2026-04-01', period_end: '2026-09-30', fiscal_year_id: 1 })
  })
})
