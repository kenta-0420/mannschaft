import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import BackButton from '~/components/BackButton.vue'

/**
 * BackButton.vue のユニットテスト。
 *
 * - label 未指定なら i18n の共通「戻る」キー（ja デフォルト=戻る）が描画される
 * - label 指定でそのラベルが描画される
 * - to 指定で NuxtLink (<a>) になり href が一致
 * - to 未指定なら button 要素になる
 */

describe('BackButton.vue', () => {
  it('label 未指定なら i18n デフォルト「戻る」が描画される', async () => {
    const wrapper = await mountSuspended(BackButton)
    // デフォルトロケール ja の common.button.back = 戻る
    expect(wrapper.text()).toContain('戻る')
  })

  it('label 指定でそのラベルが描画される', async () => {
    const wrapper = await mountSuspended(BackButton, {
      props: { label: '一覧へ戻る' },
    })
    expect(wrapper.text()).toContain('一覧へ戻る')
  })

  it('to 指定で NuxtLink になり href が一致', async () => {
    const wrapper = await mountSuspended(BackButton, {
      props: { to: '/bar' },
    })
    const anchor = wrapper.find('a')
    expect(anchor.exists()).toBe(true)
    expect(anchor.attributes('href')).toBe('/bar')
  })

  it('to 未指定なら button 要素になる', async () => {
    const wrapper = await mountSuspended(BackButton)
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.find('a').exists()).toBe(false)
  })
})
