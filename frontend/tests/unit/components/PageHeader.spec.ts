import { describe, it, expect } from 'vitest'
import { h, createCommentVNode } from 'vue'
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
const MOUNT_TIMEOUT = 600000

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

/** CMP-261014-2035: 新しい副題契約と既存スロット位置の回帰検査。 */
describe('PageHeader 副題契約', () => {
  it.each([undefined, ''])('P1 副題%jではpを描画せずタイトル行のmb-5を維持する', async (subtitle) => {
    const wrapper = await mountSuspended(PageHeader, { props: { title: '見出し', back: false }, attrs: { subtitle } })
    expect(wrapper.find('[data-testid="page-header-subtitle"]').exists()).toBe(false)
    expect(wrapper.find('h1').element.parentElement?.classList.contains('mb-5')).toBe(true)
    wrapper.unmount()
  }, MOUNT_TIMEOUT)

  it('P2 subtitle propはタイトル行の外で表示する', async () => {
    const wrapper = await mountSuspended(PageHeader, { props: { title: '見出し', back: false }, attrs: { subtitle: '副題の文言' } })
    const subtitle = wrapper.get('[data-testid="page-header-subtitle"]')
    expect(subtitle.element.tagName).toBe('P')
    expect(subtitle.text()).toBe('副題の文言')
    expect(subtitle.element.parentElement).toBe(wrapper.element)
    expect(wrapper.find('h1').element.parentElement?.contains(subtitle.element)).toBe(false)
    expect(wrapper.find('h1').element.parentElement?.classList.contains('mb-5')).toBe(false)
    wrapper.unmount()
  }, MOUNT_TIMEOUT)

  it('P2 propとslotが両方あればslotを優先する', async () => {
    const wrapper = await mountSuspended(PageHeader, {
      props: { title: '見出し', back: false }, attrs: { subtitle: 'propの副題' },
      slots: { subtitle: () => h('span', 'slotの副題') },
    })
    expect(wrapper.get('[data-testid="page-header-subtitle"]').text()).toBe('slotの副題')
    expect(wrapper.text()).not.toContain('propの副題')
    wrapper.unmount()
  }, MOUNT_TIMEOUT)

  it('P2 v-if=falseのsubtitle slotはpropに戻らずpを描画しない', async () => {
    const wrapper = await mountSuspended(PageHeader, {
      props: { title: '見出し', back: false }, attrs: { subtitle: 'propの副題' },
      slots: { subtitle: () => [createCommentVNode('v-if')] },
    })
    expect(wrapper.find('[data-testid="page-header-subtitle"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('propの副題')
    expect(wrapper.find('h1').element.parentElement?.classList.contains('mb-5')).toBe(true)
    wrapper.unmount()
  }, MOUNT_TIMEOUT)

  it('P3 help・actions・既定Tagを同時に置いても順序とhelp emitを維持する', async () => {
    const wrapper = await mountSuspended(PageHeader, {
      props: { title: '見出し', back: false, help: true },
      slots: {
        default: () => h('span', { 'data-testid': 'tag' }, 'Tag'),
        actions: () => h('button', { 'data-testid': 'header-action' }, '操作'),
      },
    })
    const h1 = wrapper.get('h1').element
    expect(h1.nextElementSibling).toBe(wrapper.get('[data-testid="tag"]').element)
    const group = h1.parentElement?.lastElementChild
    expect(group?.querySelector('[data-testid="page-header-help"]')).not.toBeNull()
    expect(group?.lastElementChild).toBe(wrapper.get('[data-testid="header-action"]').element)
    await wrapper.get('[data-testid="page-header-help"]').trigger('click')
    expect(wrapper.emitted('help')).toEqual([[]])
    wrapper.unmount()
  }, MOUNT_TIMEOUT)

  it('P4 classはタイトル行に付きsize=smはtext-xlを維持する', async () => {
    const wrapper = await mountSuspended(PageHeader, { props: { title: '見出し', size: 'sm', back: false }, attrs: { class: 'mb-4' } })
    expect(wrapper.get('h1').classes()).toContain('text-xl')
    expect(wrapper.get('h1').element.parentElement?.classList.contains('mb-4')).toBe(true)
    expect(wrapper.classes()).not.toContain('mb-4')
    wrapper.unmount()
  }, MOUNT_TIMEOUT)

  it('P5 backToとbackLabelの指定を維持する', async () => {
    const wrapper = await mountSuspended(PageHeader, { props: { title: '見出し', backTo: '/teams/example', backLabel: '一覧に戻る' } })
    expect(wrapper.get('a').attributes('href')).toBe('/teams/example')
    expect(wrapper.get('a').text()).toContain('一覧に戻る')
    wrapper.unmount()
  }, MOUNT_TIMEOUT)
})
