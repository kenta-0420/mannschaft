// @vitest-environment happy-dom
import { createI18n } from 'vue-i18n'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import jaMessages from '~/locales/ja/ranch.json'
import enMessages from '~/locales/en/ranch.json'
import zhMessages from '~/locales/zh/ranch.json'
import koMessages from '~/locales/ko/ranch.json'
import esMessages from '~/locales/es/ranch.json'
import deMessages from '~/locales/de/ranch.json'
import type { DiagnosisResult } from '~/types/ranch'
import RanchAxisResults from './RanchAxisResults.vue'

const AXES = ['FAMILIAR_NEW', 'FOCUS_VARIETY', 'SPONTANEOUS_PLAN', 'SOLO_TOGETHER', 'EXPRESSION', 'NOTICE']
function savedResult(): DiagnosisResult {
  return {
    id: 'saved', method: 'DIAGNOSIS', completedAt: '2026-10-03T03:00:00Z', resultSchemaVersion: 'diagnosis-result-v1',
    typeCode: '010101', descriptionSnapshot: { ja: '保存説明' },
    axes: Object.fromEntries(AXES.map(axis => [axis, 0])),
    axisSelections: Object.fromEntries([...AXES].reverse().map(axis => [axis, {
      side: AXES.indexOf(axis) % 2 as 0 | 1,
      zero: { ja: `${axis}の保存左ラベル` }, one: { ja: `${axis}の保存右ラベル` },
    }])),
  }
}
function render(result: DiagnosisResult, locale: 'ja' | 'en' = 'ja') {
  return mount(RanchAxisResults, { props: { result }, global: { plugins: [createI18n({ legacy: false, locale, messages: { ja: jaMessages, en: enMessages, zh: zhMessages, ko: koMessages, es: esMessages, de: deMessages } })] } })
}
describe('診断結果の本人傾向', () => {
  it('逆順のMapとスコア0でも軸キーの選択側を示し、6軸の順を維持する', () => {
    const wrapper = render(savedResult())
    expect(wrapper.findAll('li').map(row => row.attributes('data-axis'))).toEqual(AXES)
    const focus = wrapper.find('[data-axis="FOCUS_VARIETY"]')
    expect(focus.text()).toContain('FOCUS_VARIETYの保存右ラベル')
    expect(focus.text()).not.toContain('FOCUS_VARIETYの保存左ラベル')
    expect(focus.text()).toContain(jaMessages.ranch.diagnosisResults.tieSelection)
  })
  it('旧結果の補足が無いときはtypeCodeや説明を分割して推測しない', () => {
    const result = savedResult(); result.axisSelections = null
    const wrapper = render(result)
    expect(wrapper.findAll('li')).toHaveLength(0)
    expect(wrapper.text()).toContain(jaMessages.ranch.diagnosisResults.labelsUnavailable)
  })
  it('保存ラベルの英語が欠落しても日本語を示し、軸名とUI説明は英語になる', () => {
    const wrapper = render(savedResult(), 'en')
    expect(wrapper.text()).toContain(enMessages.ranch.diagnosisResults.tendencies)
    expect(wrapper.text()).toContain('NOTICEの保存右ラベル')
  })
  it('英語画面で日本語草案を表示するときは初期草案と翻訳未承認を明示する', () => {
    const result = savedResult(); result.questionnaireVersion = 'draft-20261003-v1'
    const wrapper = render(result, 'en')
    expect(wrapper.text()).toContain(enMessages.ranch.diagnosisResults.draftNotice)
    expect(wrapper.text()).toContain('not approved')
  })
  it('草案以外の保存結果へ未承認との断定を付けない', () => {
    const wrapper = render(savedResult(), 'en')
    expect(wrapper.text()).not.toContain(enMessages.ranch.diagnosisResults.draftNotice)
  })
})
