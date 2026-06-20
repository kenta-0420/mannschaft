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

// Nuxt ランタイム環境では初回 mountSuspended が遅延コンパイルを伴うため、
// 既定 5s では不足する環境がある。十分な余裕を持たせる。
const MOUNT_TIMEOUT = 30000

describe('BackButton.vue', () => {
  it(
    'label 未指定なら i18n の common.button.back にフォールバックする',
    async () => {
      const wrapper = await mountSuspended(BackButton)
      // テスト環境では i18n が翻訳値（例: ja=戻る / en=Back）を返すか、
      // 未ロード時はキーがそのまま出るかのどちらか（既存スペックの慣例）。
      // label を渡さなくても i18n フォールバック経由でラベルが描画されることを確認する。
      const text = wrapper.text()
      expect(text.length).toBeGreaterThan(0)
      // 既知の翻訳値またはキー自体のいずれかであること（直書き '' でないこと）
      const acceptable = ['戻る', 'Back', '返回', '뒤로', 'Volver', 'Zuruck', 'common.button.back']
      expect(acceptable.some((v) => text.includes(v))).toBe(true)
    },
    MOUNT_TIMEOUT,
  )

  it(
    'label 指定でそのラベルが描画される',
    async () => {
      const wrapper = await mountSuspended(BackButton, {
        props: { label: '一覧へ戻る' },
      })
      expect(wrapper.text()).toContain('一覧へ戻る')
    },
    MOUNT_TIMEOUT,
  )

  it(
    'to 指定で NuxtLink になり href が一致',
    async () => {
      const wrapper = await mountSuspended(BackButton, {
        props: { to: '/bar' },
      })
      const anchor = wrapper.find('a')
      expect(anchor.exists()).toBe(true)
      expect(anchor.attributes('href')).toBe('/bar')
    },
    MOUNT_TIMEOUT,
  )

  it(
    'to 未指定なら button 要素になる',
    async () => {
      const wrapper = await mountSuspended(BackButton)
      expect(wrapper.find('button').exists()).toBe(true)
      expect(wrapper.find('a').exists()).toBe(false)
    },
    MOUNT_TIMEOUT,
  )
})
