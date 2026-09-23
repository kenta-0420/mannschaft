import { mountSuspended } from '@nuxt/test-utils/runtime'
import { describe, expect, it } from 'vitest'
import DashboardPersonalWidgetGrid from './DashboardPersonalWidgetGrid.vue'
import type { WidgetDefinition } from '~/composables/useDashboardWidgets'

const widget = (key: string): WidgetDefinition => ({
  key,
  label: key,
  labelKey: `dashboard.widget_labels.${key}`,
  icon: 'pi pi-circle',
  description: key,
  descriptionKey: `dashboard.widget_descriptions.${key}`,
  scope: ['personal'],
})

const stubs = {
  DashboardWidgetCard: {
    template: '<article data-widget-collapsed="true"><slot /></article>',
  },
  SectionCard: { template: '<section data-testid="calendar-card"><slot /></section>' },
  WidgetMyCalendar: { template: '<div>calendar</div>' },
  WidgetNotices: { template: '<section data-testid="notices-card">notices</section>' },
}

async function mountGrid(widgets: WidgetDefinition[], collapsedKeys = new Set<string>()) {
  return mountSuspended(DashboardPersonalWidgetGrid, {
    props: { widgets, collapsedKeys },
    attrs: { class: 'grid gap-4' },
    global: { stubs },
  })
}

describe('DashboardPersonalWidgetGrid', () => {
  it('外部から渡されたグリッドclassを実DOMルートへ継承し、カードを直接の子にする', async () => {
    const wrapper = await mountGrid([widget('my-calendar'), widget('notices')])

    expect(wrapper.classes()).toEqual(expect.arrayContaining(['grid', 'gap-4']))
    expect(wrapper.element.children).toHaveLength(2)
    expect(wrapper.get('[data-testid="calendar-card"]').element.parentElement?.parentElement).toBe(
      wrapper.element,
    )
    expect(wrapper.get('[data-testid="notices-card"]').element.parentElement?.parentElement).toBe(
      wrapper.element,
    )
  })

  it('1件でも単一ルートグリッドとカードを表示し、今すぐ用のspan指定を維持する', async () => {
    const wrapper = await mountGrid([widget('my-calendar')])

    expect(wrapper.element.children).toHaveLength(1)
    expect(wrapper.find('[data-testid="calendar-card"]').exists()).toBe(true)
    expect(wrapper.element.firstElementChild?.classList).toContain('md:col-span-2')
  })

  it('noticesのmd:col-span-2指定を維持する', async () => {
    const wrapper = await mountGrid([widget('notices')])

    expect(wrapper.element.firstElementChild?.classList).toContain('md:col-span-2')
  })

  it('折りたたんだカードをモバイルでは1行全幅にし、説明文をGridで開閉する', async () => {
    const wrapper = await mountGrid(
      [widget('compact-a'), widget('compact-b')],
      new Set(['compact-a']),
    )
    const [collapsedCard, expandedCard] = Array.from(wrapper.element.children) as HTMLElement[]

    expect(collapsedCard?.classList).toContain('max-md:col-span-full')
    expect(expandedCard?.classList).not.toContain('max-md:col-span-full')
    expect(collapsedCard?.querySelector('.grid')?.className).toContain('grid-rows-[0fr]')
    expect(collapsedCard?.querySelector('.grid')?.className).toContain('opacity-0')
    expect(expandedCard?.querySelector('.grid')?.className).toContain('md:grid-rows-[1fr]')
    expect(expandedCard?.querySelector('.grid')?.className).toContain('md:opacity-100')
  })

  it('実際に折りたたまれた子カードを全幅化する項目クラスを付与する', async () => {
    const wrapper = await mountGrid([widget('compact-a')])
    const item = wrapper.element.firstElementChild

    expect(item?.classList).toContain('personal-widget-grid-item')
    expect(item?.querySelector('[data-widget-collapsed="true"]')).not.toBeNull()
  })
})
