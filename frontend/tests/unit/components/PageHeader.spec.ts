import { describe, it, expect } from 'vitest'
import { h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import PageHeader from '~/components/PageHeader.vue'

/**
 * PageHeader.vue のユニットテスト。
 *
 * - title が h1 に描画される
 * - 既定（back 未指定）で戻るリンク（BackButton）が描画される
 * - :back="false" で戻るリンクが描画されない（DOM 不在）
 * - back-to 指定で戻るリンクが NuxtLink (<a>) になり href が一致
 * - デフォルト slot の内容がタイトル行に描画される（slot 非干渉）
 */

const slotButton = () =>
  h('button', { 'data-testid': 'action' }, 'アクション')

// Nuxt ランタイム環境では初回 mountSuspended が遅延コンパイルを伴うため、
// 既定 5s では不足する環境がある。十分な余裕を持たせる。
const MOUNT_TIMEOUT = 30000

describe('PageHeader.vue', () => {
  it(
    'title が h1 に描画される',
    async () => {
      const wrapper = await mountSuspended(PageHeader, {
        props: { title: 'ページタイトル' },
      })

      const h1 = wrapper.find('h1')
      expect(h1.exists()).toBe(true)
      expect(h1.text()).toBe('ページタイトル')
    },
    MOUNT_TIMEOUT,
  )

  it(
    '既定（back 未指定）で戻るリンクが描画される',
    async () => {
      const wrapper = await mountSuspended(PageHeader, {
        props: { title: 'ページタイトル' },
      })

      // backTo 未指定 → router.back() の button 要素
      const backButton = wrapper.find('button')
      expect(backButton.exists()).toBe(true)
      expect(backButton.find('i.pi-arrow-left').exists()).toBe(true)
    },
    MOUNT_TIMEOUT,
  )

  it(
    ':back="false" で戻るリンクが描画されない',
    async () => {
      const wrapper = await mountSuspended(PageHeader, {
        props: { title: 'ページタイトル', back: false },
      })

      // 戻りアイコンが存在しないこと
      expect(wrapper.find('i.pi-arrow-left').exists()).toBe(false)
      // back ボタンも存在しないこと
      expect(wrapper.find('button').exists()).toBe(false)
    },
    MOUNT_TIMEOUT,
  )

  it(
    'back-to 指定で戻るリンクが NuxtLink になり href が一致',
    async () => {
      const wrapper = await mountSuspended(PageHeader, {
        props: { title: 'ページタイトル', backTo: '/foo' },
      })

      const anchor = wrapper.find('a')
      expect(anchor.exists()).toBe(true)
      expect(anchor.attributes('href')).toBe('/foo')
      expect(anchor.find('i.pi-arrow-left').exists()).toBe(true)
    },
    MOUNT_TIMEOUT,
  )

  it(
    'デフォルト slot の内容がタイトル行に描画される（slot 非干渉）',
    async () => {
      const wrapper = await mountSuspended(PageHeader, {
        props: { title: 'ページタイトル' },
        slots: { default: slotButton },
      })

      const action = wrapper.find('[data-testid="action"]')
      expect(action.exists()).toBe(true)
      expect(action.text()).toBe('アクション')

      // slot はタイトル行（h1 の兄弟）に並び、戻るリンクの行ではない
      const titleRow = wrapper.find('h1').element.parentElement
      expect(titleRow?.querySelector('[data-testid="action"]')).not.toBeNull()
    },
    MOUNT_TIMEOUT,
  )
})
