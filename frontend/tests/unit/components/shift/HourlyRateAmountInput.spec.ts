// @vitest-environment happy-dom
// このテストは PrimeVue の InputNumber と対象コンポーネントしか触らないため Nuxt ランタイムを要さない。
// 既定の environment: 'nuxt' は 1 ファイルごとに Nuxt 環境を構築し、本作業木では
// setup フックが 120 秒でタイムアウトしてテストが 1 件も実行されなかった。
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import InputNumber from 'primevue/inputnumber'
import HourlyRateAmountInput from '~/components/shift/HourlyRateAmountInput.vue'

const globalMountOptions = {
  plugins: [PrimeVue],
  components: { InputNumber },
}

/**
 * HourlyRateAmountInput.vue（時給入力欄）ユニットテスト — Codex 検分の指摘2件の番人。
 *
 * 観点:
 *   HR-A11Y-001: 検証エラー時、`aria-describedby` は内側の `<input>` に付く
 *                （PrimeVue の InputNumber は inheritAttrs:false なので、素の属性指定では
 *                 ルートの `<span>` に付いてしまい支援技術に届かない）
 *   HR-A11Y-002: エラー説明要素の id が `aria-describedby` の値と一致する
 *   HR-A11Y-003: エラーが無いときは `aria-describedby` を付けない（存在しない id を指さない）
 *   HR-CLEAR-001: 打鍵の時点で clear-error を emit する（v-model は blur まで更新されないため、
 *                 v-model の watch では打鍵中にエラーを消せない）
 *   HR-CLEAR-002: その打鍵では v-model はまだ更新されない（上の前提そのものの実証）
 */

const ERROR_ID = 'hourly-rate-error'

describe('HourlyRateAmountInput', () => {
  it('HR-A11Y-001/002: エラー時、内側の input に aria-describedby が付き、説明要素の id と一致する', async () => {
    const wrapper = mount(HourlyRateAmountInput, {
      props: { modelValue: 0, errorMessage: '時給は1円以上で入力してください', errorId: ERROR_ID },
      global: globalMountOptions,
    })
    const input = wrapper.find('input')
    expect(input.exists()).toBe(true)
    expect(input.attributes('aria-describedby')).toBe(ERROR_ID)
    const help = wrapper.find(`#${ERROR_ID}`)
    expect(help.exists()).toBe(true)
    expect(help.element.tagName.toLowerCase()).not.toBe('input')
    expect(help.text()).toContain('1円以上')
  })

  it('HR-A11Y-003: エラーが無いときは aria-describedby を付けない', async () => {
    const wrapper = mount(HourlyRateAmountInput, {
      props: { modelValue: 1200, errorMessage: null, errorId: ERROR_ID },
      global: globalMountOptions,
    })
    expect(wrapper.find('input').attributes('aria-describedby')).toBeUndefined()
  })

  it('HR-CLEAR-001/002: 打鍵の時点で clear-error を emit する（v-model はまだ更新されない）', async () => {
    const wrapper = mount(HourlyRateAmountInput, {
      props: { modelValue: 0, errorMessage: '時給は1円以上で入力してください', errorId: ERROR_ID },
      global: globalMountOptions,
    })
    const input = wrapper.find('input')
    // PrimeVue の InputNumber は keypress で桁を受け取り input イベントを発火する
    // （v-model への書き戻しは blur 時）。
    await input.trigger('keypress', { key: '5', charCode: 53, which: 53, keyCode: 53 })
    expect(wrapper.emitted('clear-error')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')).toBeFalsy()
  })
})
