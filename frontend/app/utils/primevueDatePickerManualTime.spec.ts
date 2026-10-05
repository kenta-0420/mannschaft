// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import DatePicker from 'primevue/datepicker'
import PrimeVue from 'primevue/config'
import { applyDatePickerManualInputFix } from './primevueDatePickerManualInput'
import { toLocalDateTimeString } from './localDate'

applyDatePickerManualInputFix(DatePicker)

const ownedContainers: HTMLElement[] = []
afterEach(() => {
  for (const container of ownedContainers.splice(0)) container.remove()
})

/** 実コンポーネントの入力イベントから親の v-model までを通す。 */
async function mountPicker(hourFormat = '24', initialValue: Date | null = null, showTime = true) {
  const Host = defineComponent({
    components: { DatePicker },
    setup() {
      const model = ref<Date | null>(initialValue)
      return { model, hourFormat, showTime }
    },
    template: '<DatePicker v-model="model" date-format="yy/mm/dd" :show-time="showTime" :hour-format="hourFormat" />',
  })
  const container = document.createElement('div')
  document.body.appendChild(container)
  ownedContainers.push(container)
  const host = mount(Host, {
    attachTo: container,
    global: { plugins: [PrimeVue] },
  })
  await nextTick()
  const picker = host.findComponent(DatePicker)
  const input = host.get('input').element as HTMLInputElement
  input.focus()
  return { host, picker, input }
}

/** DOM の現在のキャレットへ一文字ずつ入力し、実 onInput を呼ぶ。 */
async function typeInto(input: HTMLInputElement, text: string): Promise<void> {
  for (const character of text) {
    const position = input.selectionStart ?? input.value.length
    input.value = input.value.slice(0, position) + character + input.value.slice(position)
    input.setSelectionRange(position + 1, position + 1)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    await nextTick()
  }
}

async function blur(input: HTMLInputElement): Promise<void> {
  input.blur()
  await nextTick()
  await nextTick()
}

function modelDate(value: unknown): Date {
  expect(value).toBeInstanceOf(Date)
  if (!(value instanceof Date)) throw new Error('DatePicker のモデルが Date ではない')
  return value
}

describe('DatePicker の24時間手入力（CMP-260910-1557）', () => {
  it.each([
    ['17:30', '2026-10-11T17:30', 17, 30],
    ['00:00', '2026-10-11T00:00', 0, 0],
    ['23:59', '2026-10-11T23:59', 23, 59],
  ])('AM/PM のない %s が実 v-model と blur 後の表示へ保存される', async (time, expected, hour, minute) => {
    const { picker, input } = await mountPicker()
    const typed = `2026/10/11 ${time}`

    await typeInto(input, typed)

    expect(input.value).toBe(typed)
    expect(input.selectionStart).toBe(typed.length)
    expect(input.selectionEnd).toBe(typed.length)
    const value = modelDate(picker.props('modelValue'))
    expect(toLocalDateTimeString(value)).toBe(expected)
    expect([value.getHours(), value.getMinutes(), value.getSeconds()]).toEqual([hour, minute, 0])
    await blur(input)
    expect(input.value).toBe(typed)
    expect(toLocalDateTimeString(modelDate(picker.props('modelValue')))).toBe(expected)
  })

  it.each(['24:00', '17:60', '17:'])('不正または入力途中の %s は既存モデルを壊さない', async (time) => {
    const initial = new Date(2026, 9, 11, 9, 15, 0)
    const { picker, input } = await mountPicker('24', initial)
    input.select()
    // 全選択後の置換は DOM の操作だけで行い、モデルを直接更新しない。
    input.value = ''
    input.setSelectionRange(0, 0)
    const typed = `2026/10/11 ${time}`

    await typeInto(input, typed)

    expect(input.value).toBe(typed)
    expect(input.selectionStart).toBe(typed.length)
    expect(toLocalDateTimeString(modelDate(picker.props('modelValue')))).toBe('2026-10-11T09:15')
    await blur(input)
    expect(input.value).toBe('2026/10/11 09:15')
    expect(toLocalDateTimeString(modelDate(picker.props('modelValue')))).toBe('2026-10-11T09:15')
  })
})

describe('DatePicker の既存入力契約を維持する', () => {
  it.each([
    ['05:30 PM', '2026-10-11T17:30'],
    ['12:00 AM', '2026-10-11T00:00'],
  ])('12時間形式の %s は既存の AM/PM 解釈を使う', async (time, expected) => {
    const { picker, input } = await mountPicker('12')
    const typed = `2026/10/11 ${time}`

    await typeInto(input, typed)

    expect(toLocalDateTimeString(modelDate(picker.props('modelValue')))).toBe(expected)
    await blur(input)
    expect(input.value).toBe(typed)
  })

  it('12時間形式で AM/PM が欠落した入力は拒否を維持する', async () => {
    const initial = new Date(2026, 9, 11, 9, 15, 0)
    const { picker, input } = await mountPicker('12', initial)
    input.value = ''
    input.setSelectionRange(0, 0)

    await typeInto(input, '2026/10/11 05:30')

    expect(toLocalDateTimeString(modelDate(picker.props('modelValue')))).toBe('2026-10-11T09:15')
    await blur(input)
    expect(input.value).toBe('2026/10/11 09:15 AM')
  })

  it('日付だけの入力は打鍵とキャレットを保持して確定する', async () => {
    const { picker, input } = await mountPicker('24', null, false)
    const typed = '2026/10/30'

    await typeInto(input, typed)

    const value = modelDate(picker.props('modelValue'))
    expect([value.getFullYear(), value.getMonth(), value.getDate()]).toEqual([2026, 9, 30])
    expect(input.value).toBe(typed)
    expect(input.selectionStart).toBe(typed.length)
    await blur(input)
    expect(input.value).toBe(typed)
  })

  it('全体補正を繰り返し適用してもメソッドを再包装しない', () => {
    const component = DatePicker as unknown as {
      methods?: { onInput?: unknown; populateTime?: unknown }
    }
    const methods = component.methods
    expect(methods).toBeDefined()
    const inputBefore = methods?.onInput
    const populateTimeBefore = methods?.populateTime

    applyDatePickerManualInputFix(DatePicker)

    expect(component.methods?.onInput).toBe(inputBefore)
    expect(component.methods?.populateTime).toBe(populateTimeBefore)
  })
})
