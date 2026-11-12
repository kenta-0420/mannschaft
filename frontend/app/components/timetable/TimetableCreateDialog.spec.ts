import { describe, expect, it } from 'vitest'

import { mountSuspended } from '@nuxt/test-utils/runtime'
import TimetableCreateDialog from './TimetableCreateDialog.vue'
import type { TimetableTerm } from '~/types/timetable'

/**
 * CMP-261105-1507 B3: 時間割作成の失敗理由を画面に出す。
 * 適用終了日が開始日より前なら送信前に弾く。BE の fieldErrors / formError は欄とフォームに出す。
 */
const stubs = {
  Dialog: {
    props: ['visible'],
    template: '<div v-if="visible"><slot /><slot name="footer" /></div>',
  },
  InputText: {
    props: ['modelValue', 'invalid'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" :data-invalid="invalid" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Select: true,
  Textarea: true,
  Button: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const terms = [{ id: 7, name: '1学期' }] as unknown as TimetableTerm[]

describe('TimetableCreateDialog', () => {
  it('formError があればフォームにその理由を出す', async () => {
    const wrapper = await mountSuspended(TimetableCreateDialog, {
      props: { visible: true, terms, submitting: false, formError: '有効期間が学期の範囲外です' },
      global: { stubs },
    })
    expect(wrapper.find('[data-testid="timetable-create-form-error"]').text()).toBe('有効期間が学期の範囲外です')
  })

  it('fieldErrors を該当欄に出す', async () => {
    const wrapper = await mountSuspended(TimetableCreateDialog, {
      props: {
        visible: true,
        terms,
        submitting: false,
        fieldErrors: { effectiveFrom: '開始日が不正です', name: '名前が長すぎます' },
      },
      global: { stubs },
    })
    expect(wrapper.find('[data-testid="timetable-create-error-effectiveFrom"]').text()).toBe('開始日が不正です')
    expect(wrapper.find('[data-testid="timetable-create-error-name"]').text()).toBe('名前が長すぎます')
  })

  it('適用終了日が適用開始日より前なら送信せず、終了日の欄にエラーを出す', async () => {
    const wrapper = await mountSuspended(TimetableCreateDialog, {
      props: { visible: false, terms, submitting: false },
      global: { stubs },
    })
    await wrapper.setProps({ visible: true })
    const inputs = wrapper.findAll('input')
    // 順序: 名前 / 適用開始日 / 適用終了日
    await inputs[0]!.setValue('時間割A')
    await inputs[1]!.setValue('2026-09-30')
    await inputs[2]!.setValue('2026-04-01')
    // Select はスタブのため termId を内部状態に直接入れられない。vm 経由で設定する。
    ;(wrapper.vm as unknown as { form: { termId: number | null } }).form.termId = 7
    await wrapper.vm.$nextTick()
    await wrapper.findAll('button').at(-1)!.trigger('click')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.find('[data-testid="timetable-create-error-effectiveUntil"]').exists()).toBe(true)
  })
})
