import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'
import ShiftRequestForm from './ShiftRequestForm.vue'
import ShiftPreferenceRadioCard from './ShiftPreferenceRadioCard.vue'

const { submitShiftRequest } = vi.hoisted(() => ({ submitShiftRequest: vi.fn() }))
mockNuxtImport('useShiftApi', () => () => ({ submitShiftRequest }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))

const Dialog = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', [slots.default?.(), slots.footer?.()]),
})
const Button = defineComponent({
  props: ['label'],
  emits: ['click'],
  setup:
    (props, { emit }) =>
    () =>
      h('button', { onClick: () => emit('click') }, String(props.label)),
})
beforeEach(() => submitShiftRequest.mockReset().mockResolvedValue({}))

async function mountForm() {
  return mountSuspended(ShiftRequestForm, {
    props: { scheduleId: 42, visible: true },
    global: {
      components: { ShiftPreferenceRadioCard },
      stubs: { Dialog, Button, DatePicker: true, InputText: true },
    },
  })
}

describe('旧シフト希望フォームの5段階API契約', () => {
  it('既定値とclose後の値はPREFERREDである', async () => {
    const wrapper = await mountForm()
    const vm = wrapper.vm as unknown as { form: { preference: string }; close: () => void }
    expect(vm.form.preference).toBe('PREFERRED')
    vm.form.preference = 'ABSOLUTE_REST'
    vm.close()
    expect(vm.form.preference).toBe('PREFERRED')
  })

  it.each(['PREFERRED', 'AVAILABLE', 'WEAK_REST', 'STRONG_REST', 'ABSOLUTE_REST'])(
    'ラジオで選んだ%sをそのまま送る',
    async (preference) => {
      const wrapper = await mountForm()
      const radios = wrapper.findAll('[role="radio"]')
      expect(radios).toHaveLength(5)
      await radios[
        ['PREFERRED', 'AVAILABLE', 'WEAK_REST', 'STRONG_REST', 'ABSOLUTE_REST'].indexOf(preference)
      ]!.trigger('click')
      const vm = wrapper.vm as unknown as {
        form: { slotDate: Date | null }
        submit: () => Promise<void>
      }
      // UTC/JSTとも同じ暦日になる昼間の固定入力。
      vm.form.slotDate = new Date('2026-10-05T03:00:00Z')
      await vm.submit()
      expect(submitShiftRequest).toHaveBeenCalledWith(
        expect.objectContaining({ scheduleId: 42, slotDate: '2026-10-05', preference }),
      )
    },
  )
})
