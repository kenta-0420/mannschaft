import { defineComponent, h, nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { afterEach, describe, expect, it, vi } from 'vitest'

const createUnit = vi.fn()
const showSuccess = vi.fn()
const handleApiError = vi.fn()
const getFieldErrors = vi.fn(() => ({}))

vi.mock('vee-validate', async () => {
  const { ref } = await import('vue')

  return {
    useForm: ({ initialValues }: { initialValues: Record<string, unknown> }) => {
      const fields = new Map(
        Object.entries(initialValues).map(([name, value]) => [name, ref(value)]),
      )

      return {
        defineField: (name: string) => [fields.get(name)],
        handleSubmit: (callback: (values: Record<string, unknown>) => Promise<void>) => async () =>
          callback(Object.fromEntries([...fields].map(([name, value]) => [name, value.value]))),
        errors: ref({}),
        resetForm: vi.fn(),
      }
    },
  }
})

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useResidentApi', () => () => ({ createUnit }))
mockNuxtImport('useNotification', () => () => ({ showSuccess }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError, getFieldErrors }))

const InputText = defineComponent({
  inheritAttrs: false,
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () =>
      h('input', {
        ...attrs,
        value: props.modelValue,
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).value),
      })
  },
})

const InputNumber = defineComponent({
  props: { modelValue: { type: Number, default: null } },
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () =>
      h('input', {
        ...attrs,
        value: props.modelValue ?? '',
        onInput: (event: Event) =>
          emit('update:modelValue', Number((event.target as HTMLInputElement).value)),
      })
  },
})

const Textarea = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () =>
      h('textarea', {
        ...attrs,
        value: props.modelValue,
        onInput: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
      })
  },
})

const Dialog = defineComponent({
  setup(_, { slots }) {
    return () => h('div', [slots.default?.(), slots.footer?.()])
  },
})

const Button = defineComponent({
  inheritAttrs: false,
  emits: ['click'],
  setup(_, { emit, attrs }) {
    return () => h('button', { ...attrs, onClick: () => emit('click') })
  },
})

const dialog = await import('./DwellingUnitCreateDialog.vue').then((module) => module.default)

function mountDialog() {
  return mountSuspended(dialog, {
    props: { visible: true, scopeType: 'team', scopeId: 'team-slug' },
    global: { stubs: { Dialog, Button, InputText, InputNumber, Textarea } },
  })
}

afterEach(() => {
  createUnit.mockReset()
  showSuccess.mockReset()
  handleApiError.mockReset()
  getFieldErrors.mockReset()
  getFieldErrors.mockReturnValue({})
})

describe('DwellingUnitCreateDialog', () => {
  it('入力した住戸番号をAPIへ渡し、成功時にcreatedをemitする', async () => {
    createUnit.mockResolvedValue(undefined)
    const wrapper = await mountDialog()

    await wrapper.get('[data-testid="dwelling-unit-number-input"]').setValue('101')
    await wrapper.get('[data-testid="dwelling-unit-create-submit"]').trigger('click')
    await flushPromises()

    expect(createUnit).toHaveBeenCalledWith(
      'team',
      'team-slug',
      expect.objectContaining({ unitNumber: '101' }),
    )
    expect(wrapper.emitted('created')).toHaveLength(1)
    expect(showSuccess).toHaveBeenCalledWith('property.residents.createSuccess')
  })

  it('送信中は同じ住戸を二重作成せず、成功時にcreatedをemitする', async () => {
    let resolveCreate: () => void = () => {}
    createUnit.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveCreate = resolve
      }),
    )
    const wrapper = await mountDialog()

    await wrapper.get('[data-testid="dwelling-unit-number-input"]').setValue('101')
    await wrapper.get('[data-testid="dwelling-unit-create-submit"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="dwelling-unit-create-submit"]').trigger('click')

    expect(createUnit).toHaveBeenCalledTimes(1)
    resolveCreate()
    await flushPromises()

    expect(wrapper.emitted('created')).toHaveLength(1)
    expect(showSuccess).toHaveBeenCalledWith('property.residents.createSuccess')
  })
})
