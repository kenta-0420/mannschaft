import { describe, it, expect } from 'vitest'
import { h } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import DashboardWidgetCard from '~/components/DashboardWidgetCard.vue'

/**
 * DashboardWidgetCard.vue のユニットテスト。
 *
 * - to 未指定なら title は <h3> のみで <a>（NuxtLink）を伴わない
 * - to 指定で title が NuxtLink (<a>) になり href が正しい
 * - scrollable=true（デフォルト）でコンテンツ領域に overflow-y-auto クラスが付く
 * - scrollable=false で overflow-y-auto クラスが付かない
 * - maxHeight プロップが style 属性に反映される
 */

const slotContent = () => h('p', { 'data-testid': 'content' }, '本文')

describe('DashboardWidgetCard.vue', () => {
  it('to 未指定なら title は <h3> のみでリンクではない', async () => {
    const wrapper = await mountSuspended(DashboardWidgetCard, {
      props: { title: 'テストタイトル', icon: 'pi pi-user' },
      slots: { default: slotContent },
    })

    const h3 = wrapper.find('h3')
    expect(h3.exists()).toBe(true)
    expect(h3.text()).toBe('テストタイトル')

    // タイトル領域の祖先に <a> が無いこと
    expect(h3.element.closest('a')).toBeNull()
  })

  it('to 指定で title が NuxtLink (<a>) になり href が正しい', async () => {
    const wrapper = await mountSuspended(DashboardWidgetCard, {
      props: { title: 'カレンダー', icon: 'pi pi-calendar', to: '/calendar' },
      slots: { default: slotContent },
    })

    const h3 = wrapper.find('h3')
    expect(h3.exists()).toBe(true)
    expect(h3.text()).toBe('カレンダー')

    // タイトルが <a> (NuxtLink) で包まれていること
    const anchor = wrapper.find('a')
    expect(anchor.exists()).toBe(true)
    expect(anchor.attributes('href')).toBe('/calendar')
    // <h3> が <a> 配下に存在
    expect(anchor.find('h3').exists()).toBe(true)
  })

  it('scrollable がデフォルト (true) でコンテンツ領域に overflow-y-auto クラスが付く', async () => {
    const wrapper = await mountSuspended(DashboardWidgetCard, {
      props: { title: 'スクロール対象' },
      slots: { default: slotContent },
    })

    const content = wrapper.find('[data-testid="content"]')
    expect(content.exists()).toBe(true)
    const parent = content.element.parentElement
    expect(parent?.className).toContain('overflow-y-auto')
    // インラインスタイル max-height が指定されていること
    expect(parent?.getAttribute('style') ?? '').toContain('max-height')
  })

  it('scrollable=false でコンテンツ領域に overflow-y-auto クラスが付かない', async () => {
    const wrapper = await mountSuspended(DashboardWidgetCard, {
      props: { title: 'スクロールなし', scrollable: false },
      slots: { default: slotContent },
    })

    const content = wrapper.find('[data-testid="content"]')
    expect(content.exists()).toBe(true)
    const parent = content.element.parentElement
    expect(parent?.className ?? '').not.toContain('overflow-y-auto')
  })

  it('maxHeight プロップが style に反映される', async () => {
    const wrapper = await mountSuspended(DashboardWidgetCard, {
      props: { title: '高さ指定', maxHeight: '12rem' },
      slots: { default: slotContent },
    })

    const content = wrapper.find('[data-testid="content"]')
    expect(content.exists()).toBe(true)
    const parent = content.element.parentElement
    expect(parent?.getAttribute('style') ?? '').toContain('12rem')
  })
})
