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
  SectionCard: { template: '<section data-testid="calendar-card"><slot /></section>' },
  WidgetMyCalendar: { template: '<div>calendar</div>' },
  WidgetNotices: { template: '<section data-testid="notices-card">notices</section>' },
}

async function mountGrid(widgets: WidgetDefinition[]) {
  return mountSuspended(DashboardPersonalWidgetGrid, {
    props: { widgets, collapsedKeys: new Set<string>() },
    attrs: { class: 'grid gap-4' },
    global: { stubs },
  })
}

describe('DashboardPersonalWidgetGrid', () => {
  it('外部から渡されたグリッドclassを実DOMルートへ継承し、カードを直接の子にする', async () => {
    const wrapper = await mountGrid([widget('my-calendar'), widget('notices')])

    expect(wrapper.classes()).toEqual(expect.arrayContaining(['grid', 'gap-4']))
    expect(wrapper.element.children).toHaveLength(2)
    expect(wrapper.get('[data-testid="calendar-card"]').element.parentElement).toBe(wrapper.element)
    expect(wrapper.get('[data-testid="notices-card"]').element.parentElement).toBe(wrapper.element)
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
})
