import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import DashboardPersonalAccordion from '~/components/dashboard/DashboardPersonalAccordion.vue'
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
const widgets = [
  'my-calendar',
  'notices',
  'upcoming-events',
  'my-recruitments',
  'return-stay-plan',
  'weather',
  'timetable-today',
  'personal-todo',
  'reflection-today',
  'todo-countdown',
  'event-dismissal-reminder',
  'unread-threads',
  'team-announcements',
  'org-announcements',
  'my-timeline',
  'recruitment-feed',
  'village-lobby-digest',
  'recent-activity',
  'quick-memo',
  'my-blog',
  'my-corkboard',
  'favorites',
  'my-teams',
  'my-organizations',
].map(widget)
const GridStub = defineComponent({
  name: 'DashboardPersonalWidgetGrid',
  props: { widgets: { type: Array, required: true } },
  template: '<div class="widget-grid">{{ widgets.map((item) => item.key).join(",") }}</div>',
})
async function mountAccordion(items = widgets) {
  return mountSuspended(DashboardPersonalAccordion, {
    props: { widgets: items, collapsedKeys: new Set<string>() },
    global: { stubs: { DashboardPersonalWidgetGrid: GridStub } },
  })
}

describe('DashboardPersonalAccordion', () => {
  it('initially closes five categories and only displays the now widgets', async () => {
    const wrapper = await mountAccordion()
    expect(wrapper.findAll('[data-testid="personal-dashboard-accordion"] button')).toHaveLength(5)
    expect(wrapper.findAll('[aria-expanded="false"]')).toHaveLength(5)
    expect(wrapper.findAll('.widget-grid')).toHaveLength(1)
    expect(wrapper.find('.widget-grid').text()).toBe('my-calendar,notices')
  })
  it('今すぐ層と展開カテゴリへ、それぞれのグリッドclassを渡す', async () => {
    const wrapper = await mountAccordion()
    const nowGrid = wrapper.get('.widget-grid')

    expect(nowGrid.classes()).toEqual(
      expect.arrayContaining([
        'grid',
        'gap-4',
        'grid-cols-[repeat(auto-fit,minmax(230px,1fr))]',
      ]),
    )

    await wrapper.get('#personal-dashboard-section-button-schedule').trigger('click')
    const scheduleGrid = wrapper.findAll('.widget-grid')[1]!
    expect(scheduleGrid.classes()).toEqual(
      expect.arrayContaining([
        'grid',
        'gap-4',
        'grid-cols-[repeat(auto-fit,minmax(220px,1fr))]',
      ]),
    )
  })
  it('opens categories independently and keeps a mounted grid after close and reopen', async () => {
    const wrapper = await mountAccordion()
    const buttons = wrapper.findAll('button')
    await buttons[0]!.trigger('click')
    await buttons[1]!.trigger('click')
    expect(wrapper.findAll('[aria-expanded="true"]')).toHaveLength(2)
    expect(wrapper.findAll('.widget-grid')).toHaveLength(3)
    await buttons[0]!.trigger('click')
    expect(wrapper.findAll('[aria-expanded="true"]')).toHaveLength(1)
    expect(wrapper.findAll('.widget-grid')).toHaveLength(3)
    const scheduleRegion = wrapper.get('#personal-dashboard-section-schedule')
    expect(scheduleRegion.classes()).toEqual(
      expect.arrayContaining(['grid-rows-[0fr]', 'opacity-0']),
    )
    expect(scheduleRegion.attributes('aria-hidden')).toBe('true')
    expect(scheduleRegion.attributes()).toHaveProperty('inert')
    await buttons[0]!.trigger('click')
    expect(scheduleRegion.classes()).toEqual(
      expect.arrayContaining(['grid-rows-[1fr]', 'opacity-100']),
    )
    expect(scheduleRegion.attributes('aria-hidden')).toBe('false')
    expect(scheduleRegion.attributes()).not.toHaveProperty('inert')
  })
  it('classifies all 24 widgets and provides ARIA plus a zero-count badge', async () => {
    const emptyWrapper = await mountAccordion([widget('my-calendar'), widget('notices')])
    const schedule = emptyWrapper.get('#personal-dashboard-section-button-schedule')
    expect(schedule.attributes('aria-controls')).toBe('personal-dashboard-section-schedule')
    expect(emptyWrapper.get('#personal-dashboard-section-schedule').attributes('role')).toBe(
      'region',
    )
    expect(schedule.find('[data-widget-count]').attributes('data-widget-count')).toBe('0')
    expect(schedule.find('[data-widget-count]').attributes('aria-label')).toBeTruthy()
    expect(emptyWrapper.text()).toContain('📆')
    expect(emptyWrapper.text()).toContain('🗂️')
    const allWidgetsWrapper = await mountAccordion()
    for (const button of allWidgetsWrapper.findAll('button')) await button.trigger('click')
    const assignedKeys = allWidgetsWrapper
      .findAll('.widget-grid')
      .flatMap((grid) => grid.text().split(','))
    expect(assignedKeys.sort()).toEqual(widgets.map((item) => item.key).sort())
  })
  it('keeps five categories when every widget is hidden and opens settings from an empty category', async () => {
    const wrapper = await mountAccordion([])
    expect(wrapper.findAll('[data-testid="personal-dashboard-accordion"] section')).toHaveLength(5)
    const schedule = wrapper.get('#personal-dashboard-section-button-schedule')
    await schedule.trigger('click')
    const region = wrapper.get('#personal-dashboard-section-schedule')
    expect(region.text()).toContain('No widgets to display')
    await region.get('button').trigger('click')
    expect(wrapper.emitted('configure')).toHaveLength(1)
  })
})
