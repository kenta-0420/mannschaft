// @vitest-environment happy-dom
import { createI18n } from 'vue-i18n'
import messages from '~/locales/ja/ranch.json'
import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import RanchNaming from './RanchNaming.vue'
const InputText = { props:['modelValue'], emits:['update:modelValue'], template:'<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }
const Button = { props:['label','disabled','type'], template:'<button :type="type || \'button\'" :disabled="disabled">{{label}}</button>' }
function render(props = {}) { return mount(RanchNaming,{props,global:{plugins:[createI18n({legacy:false,locale:'ja',messages:{ja:messages}})],stubs:{InputText,Button,SectionCard:{template:'<section><slot /></section>'}}}}) }
describe('AC61 不可逆命名の画面', () => {
 it('名前は空で始まり、入力→確認→確定でだけnormalized名を送る', async () => {
  const wrapper=render(); const input=wrapper.find('input'); expect(input.element.value).toBe('')
  await input.setValue(' e\u0301 '); await wrapper.find('form').trigger('submit'); await flushPromises()
  expect(wrapper.emitted('confirm')).toBeUndefined(); await vi.waitFor(() => expect(wrapper.text()).toContain('é'))
  await wrapper.findAll('button')[0].trigger('click'); expect(wrapper.emitted('confirm')?.[0]).toEqual(['é'])
  wrapper.unmount()
 })
 it('IME編集中は確認へ進まず確定しない', async () => {
  const wrapper=render(); const input=wrapper.find('input'); await input.setValue('恐竜'); await input.trigger('compositionstart')
  await wrapper.find('form').trigger('submit'); await flushPromises(); expect(wrapper.find('form').exists()).toBe(true); expect(wrapper.emitted('confirm')).toBeUndefined(); wrapper.unmount()
 })
 it('10文字上限違反は確認へ進ませない', async () => {
  const wrapper=render(); await wrapper.find('input').setValue('🦕'.repeat(11)); await wrapper.find('form').trigger('submit'); await flushPromises(); expect(wrapper.find('form').exists()).toBe(true); expect(wrapper.emitted('confirm')).toBeUndefined(); wrapper.unmount()
 })
})
