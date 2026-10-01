import { mountSuspended } from '@nuxt/test-utils/runtime'
import { describe, expect, it } from 'vitest'
import DashboardScopeAccordion from './DashboardScopeAccordion.vue'
import type { WidgetDefinition } from '~/composables/useDashboardWidgets'

const widget = (key: string): WidgetDefinition => ({
  key,
  label: key,
  labelKey: `dashboard.widget_labels.${key}`,
  icon: 'pi pi-circle',
  description: key,
  descriptionKey: `dashboard.widget_descriptions.${key}`,
  scope: ['team', 'organization'],
})

async function mountAccordion(widgets: WidgetDefinition[]) {
  return mountSuspended(DashboardScopeAccordion, {
    props: { widgets },
    slots: {
      default:
        '<template #default="{ widgets }"><div class="rendered-widgets">{{ widgets.map((widget) => widget.key).join(\',\') }}</div></template>',
    },
  })
}

describe('DashboardScopeAccordion', () => {
  it('ウィジェットを用途別セクションへ分類し、初期状態では内容を描画しない', async () => {
    const wrapper = await mountAccordion([
      widget('schedule'),
      widget('todos'),
      widget('timeline'),
      widget('team-match-summary'),
    ])

    expect(wrapper.findAll('section')).toHaveLength(4)
    expect(
      wrapper.find('[data-section-key="schedule-activity"] [data-widget-count="1"]').exists(),
    ).toBe(true)
    expect(
      wrapper.find('[data-section-key="tasks-confirmation"] [data-widget-count="1"]').exists(),
    ).toBe(true)
    expect(
      wrapper.find('[data-section-key="communication"] [data-widget-count="1"]').exists(),
    ).toBe(true)
    expect(
      wrapper.find('[data-section-key="results-members"] [data-widget-count="1"]').exists(),
    ).toBe(true)
    expect(wrapper.find('.rendered-widgets').exists()).toBe(false)
  })

  it('見出しを押すと対象セクションだけを展開して遅延描画する', async () => {
    const wrapper = await mountAccordion([widget('schedule'), widget('todos')])
    const button = wrapper.get('#scope-dashboard-section-button-schedule-activity')

    expect(button.attributes('aria-expanded')).toBe('false')
    await button.trigger('click')

    expect(button.attributes('aria-expanded')).toBe('true')
    expect(
      wrapper.get('#scope-dashboard-section-schedule-activity').attributes('aria-hidden'),
    ).toBe('false')
    expect(wrapper.get('.rendered-widgets').text()).toBe('schedule')
  })

  it('未分類の新規ウィジェットを最後のセクションへ残して欠落させない', async () => {
    const wrapper = await mountAccordion([widget('future-widget')])
    const section = wrapper.get('[data-section-key="results-members"]')

    expect(section.find('[data-widget-count="1"]').exists()).toBe(true)
    await section.get('button').trigger('click')
    expect(section.get('.rendered-widgets').text()).toBe('future-widget')
  })
})
