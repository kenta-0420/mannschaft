// @vitest-environment happy-dom
/**
 * CMP-260910-1557 — PrimeVue DatePicker のキーボード入力欠落の再現／回帰テスト。
 *
 * 症状: `2026/09/30` とキーボードで打つと `2026/09/03` が保存される。
 * 原因: PrimeVue の DatePicker は入力欄を `<InputText :defaultValue="inputFieldValue">`
 *       で描画しており、InputText 側は `:value="d_value"` で DOM を完全制御している。
 *       1打鍵ごとに onInput がモデルを更新すると inputFieldValue（ゼロ詰め済みの
 *       整形文字列）が変わり、Vue が利用者の打鍵途中の文字列を DOM ごと上書きする。
 *       さらに DatePicker の `updated()` が打鍵前のキャレット位置を復元するため、
 *       `2026/09/3` → `2026/09/03`（キャレットは末尾の1文字前）→ 次の `0` で
 *       `2026/09/003` という壊れた文字列になり、最終的に 09/03 が確定する。
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import DatePicker from 'primevue/datepicker'
import PrimeVue from 'primevue/config'
import {
  applyDatePickerManualInputFix,
  normalizeManualDateInput,
} from '~/utils/primevueDatePickerManualInput'

applyDatePickerManualInputFix(DatePicker)

/** 実際のキーボード入力と同じく「現在のキャレット位置に1文字挿入」を繰り返す。 */
async function typeInto(input: HTMLInputElement, text: string): Promise<void> {
  for (const char of text) {
    const pos = input.selectionStart ?? input.value.length
    input.value = input.value.slice(0, pos) + char + input.value.slice(pos)
    input.setSelectionRange(pos + 1, pos + 1)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    await nextTick()
  }
}

async function mountPicker(dateFormat = 'yy/mm/dd', selectionMode = 'single') {
  const wrapper = mount(DatePicker, {
    attachTo: document.body,
    props: {
      modelValue: null,
      dateFormat,
      selectionMode,
      'onUpdate:modelValue': (value: Date | null) => {
        void wrapper.setProps({ modelValue: value })
      },
    },
    global: { plugins: [PrimeVue] },
  })
  await nextTick()
  const input = wrapper.find('input').element as HTMLInputElement
  input.focus()
  return { wrapper, input }
}

function formatted(value: unknown): string | null {
  return value instanceof Date
    ? `${value.getFullYear()}/${String(value.getMonth() + 1).padStart(2, '0')}/${String(value.getDate()).padStart(2, '0')}`
    : null
}

describe('DatePicker 手入力（CMP-260910-1557）', () => {
  it.each([
    ['2026/09/30', '2026/09/30'],
    ['2026/09/15', '2026/09/15'],
    ['2026/12/25', '2026/12/25'],
    ['2026/09/01', '2026/09/01'],
  ])('スラッシュ区切り %s を打鍵するとそのまま確定する', async (typed, expected) => {
    const { wrapper, input } = await mountPicker()
    await typeInto(input, typed)

    expect(input.value).toBe(expected)
    expect(formatted(wrapper.props('modelValue'))).toBe(expected)
    wrapper.unmount()
  }, 30000)

  it('ハイフン区切り 2026-09-30 を打鍵しても黙って空欄にならない', async () => {
    const { wrapper, input } = await mountPicker()
    await typeInto(input, '2026-09-30')

    expect(formatted(wrapper.props('modelValue'))).toBe('2026/09/30')
    wrapper.unmount()
  }, 30000)

  // アプリ内で使われている dateFormat は `yy/mm/dd`（48箇所）と `yy-mm-dd`（26箇所）の
  // 2種類のみ。ハイフン区切りの欄でも同じ欠陥が起きないことを実際にマウントして確かめる。
  it('dateFormat が yy-mm-dd の欄でも打鍵どおり確定する', async () => {
    const { wrapper, input } = await mountPicker('yy-mm-dd')
    await typeInto(input, '2026-09-30')

    expect(input.value).toBe('2026-09-30')
    expect(formatted(wrapper.props('modelValue'))).toBe('2026/09/30')
    wrapper.unmount()
  }, 30000)

  it('dateFormat が yy-mm-dd の欄にスラッシュ区切りで打っても受理する', async () => {
    const { wrapper, input } = await mountPicker('yy-mm-dd')
    await typeInto(input, '2026/09/30')

    expect(formatted(wrapper.props('modelValue'))).toBe('2026/09/30')
    wrapper.unmount()
  }, 30000)

  it('全角数字・年月日区切りも受理する', async () => {
    const { wrapper, input } = await mountPicker()
    await typeInto(input, '２０２６年０９月３０日')

    expect(formatted(wrapper.props('modelValue'))).toBe('2026/09/30')
    wrapper.unmount()
  }, 30000)
})

// 範囲選択（range）・複数選択（multiple）は、PrimeVue が1つの入力欄の中で
// 複数の日付を区切って持つ（range は ` - `、multiple は `,`）。
// 区切り文字の正規化がこの「日付どうしの区切り」まで潰すと、parseValue が
// 分割できずモデルが更新されなくなる。現時点でコードベースに利用箇所は無いが、
// 本パッチは DatePicker の全インスタンスに効く全体適用のため、
// 将来 range の日付欄が1つ足された瞬間に無警告で壊れる。ここが唯一の防波堤。
describe('DatePicker 手入力 — 範囲選択・複数選択の区切りを潰さない', () => {
  function formattedList(value: unknown): string[] | null {
    return Array.isArray(value) ? value.map((v) => formatted(v) ?? 'null') : null
  }

  it('range: スラッシュ区切りの日付欄で範囲の ` - ` が保たれる', async () => {
    const { wrapper, input } = await mountPicker('yy/mm/dd', 'range')
    await typeInto(input, '2026/09/01 - 2026/09/30')

    expect(formattedList(wrapper.props('modelValue'))).toEqual(['2026/09/01', '2026/09/30'])
    wrapper.unmount()
  }, 30000)

  it('range: ハイフン区切りで日付を打っても範囲として解釈される', async () => {
    const { wrapper, input } = await mountPicker('yy/mm/dd', 'range')
    await typeInto(input, '2026-09-01 - 2026-09-30')

    expect(formattedList(wrapper.props('modelValue'))).toEqual(['2026/09/01', '2026/09/30'])
    wrapper.unmount()
  }, 30000)

  it('range: dateFormat が yy-mm-dd でも範囲の ` - ` と日付の `-` を取り違えない', async () => {
    const { wrapper, input } = await mountPicker('yy-mm-dd', 'range')
    await typeInto(input, '2026-09-01 - 2026-09-30')

    expect(formattedList(wrapper.props('modelValue'))).toEqual(['2026/09/01', '2026/09/30'])
    wrapper.unmount()
  }, 30000)

  it('multiple: カンマ区切りの複数日付が保たれる', async () => {
    const { wrapper, input } = await mountPicker('yy/mm/dd', 'multiple')
    await typeInto(input, '2026/09/01, 2026/09/30')

    expect(formattedList(wrapper.props('modelValue'))).toEqual(['2026/09/01', '2026/09/30'])
    wrapper.unmount()
  }, 30000)

  it('multiple: dateFormat が yy-mm-dd の欄にスラッシュ区切りで打っても受理する', async () => {
    const { wrapper, input } = await mountPicker('yy-mm-dd', 'multiple')
    await typeInto(input, '2026/09/01, 2026/09/30')

    expect(formattedList(wrapper.props('modelValue'))).toEqual(['2026/09/01', '2026/09/30'])
    wrapper.unmount()
  }, 30000)
})

describe('normalizeManualDateInput', () => {
  it('dateFormat の区切り文字に合わせて区切りを正規化する', () => {
    expect(normalizeManualDateInput('2026-09-30', 'yy/mm/dd')).toBe('2026/09/30')
    expect(normalizeManualDateInput('2026.09.30', 'yy/mm/dd')).toBe('2026/09/30')
    expect(normalizeManualDateInput('2026/09/30', 'dd-mm-yy')).toBe('2026-09-30')
  })

  it('全角数字と年月日表記を受理する', () => {
    expect(normalizeManualDateInput('２０２６年９月３０日', 'yy/mm/dd')).toBe('2026/9/30')
  })

  it('時刻の区切り（コロン）は書き換えない', () => {
    expect(normalizeManualDateInput('2026-09-30 13:45', 'yy/mm/dd')).toBe('2026/09/30 13:45')
  })

  it('range では範囲の区切り ` - ` を保ったまま各日付を正規化する', () => {
    expect(normalizeManualDateInput('2026-09-01 - 2026-09-30', 'yy/mm/dd', 'range')).toBe(
      '2026/09/01 - 2026/09/30',
    )
    expect(normalizeManualDateInput('2026/09/01 - 2026/09/30', 'yy-mm-dd', 'range')).toBe(
      '2026-09-01 - 2026-09-30',
    )
  })

  it('multiple では日付どうしのカンマ区切りを保つ', () => {
    expect(normalizeManualDateInput('2026-09-01, 2026-09-30', 'yy/mm/dd', 'multiple')).toBe(
      '2026/09/01, 2026/09/30',
    )
  })

  it('入力途中の文字列を壊さない', () => {
    expect(normalizeManualDateInput('2026/09/3', 'yy/mm/dd')).toBe('2026/09/3')
    expect(normalizeManualDateInput('', 'yy/mm/dd')).toBe('')
  })
})
