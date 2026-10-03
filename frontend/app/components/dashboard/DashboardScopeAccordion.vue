<script setup lang="ts">
import type { WidgetDefinition } from '~/composables/useDashboardWidgets'

type SectionKey = 'schedule-activity' | 'tasks-confirmation' | 'communication' | 'results-members'

interface SectionDefinition {
  key: SectionKey
  labelKey: string
  icon: string
  widgetKeys: readonly string[]
}

const props = defineProps<{ widgets: WidgetDefinition[] }>()
const emit = defineEmits<{ configure: [] }>()
defineSlots<{ default(props: { widgets: WidgetDefinition[] }): unknown }>()

const { t } = useI18n()

const sections: readonly SectionDefinition[] = [
  {
    key: 'schedule-activity',
    labelKey: 'dashboard.scope_accordion.schedule_activity',
    icon: 'pi pi-calendar',
    widgetKeys: ['upcoming-events', 'schedule', 'attendance-results', 'activities'],
  },
  {
    key: 'tasks-confirmation',
    labelKey: 'dashboard.scope_accordion.tasks_confirmation',
    icon: 'pi pi-check-square',
    widgetKeys: ['todos', 'surveys', 'survey-results', 'circulation', 'projects'],
  },
  {
    key: 'communication',
    labelKey: 'dashboard.scope_accordion.communication',
    icon: 'pi pi-comments',
    widgetKeys: ['timeline', 'bulletin', 'blog', 'chat', 'gallery'],
  },
  {
    key: 'results-members',
    labelKey: 'dashboard.scope_accordion.results_members',
    icon: 'pi pi-trophy',
    widgetKeys: [
      'members',
      'member-info',
      'team-standings-record',
      'team-division-standings',
      'org-tournament-summary',
      'team-match-summary',
    ],
  },
]

const assignedWidgetKeys = new Set(sections.flatMap((section) => [...section.widgetKeys]))
const sectionWidgets = computed(
  () =>
    new Map(
      sections.map((section) => {
        const widgets = props.widgets.filter(
          (widget) =>
            section.widgetKeys.includes(widget.key) ||
            (section.key === 'results-members' && !assignedWidgetKeys.has(widget.key)),
        )
        return [section.key, widgets]
      }),
    ),
)

const expandedKeys = ref<Set<SectionKey>>(new Set())
const mountedKeys = ref<Set<SectionKey>>(new Set())

function isExpanded(key: SectionKey): boolean {
  return expandedKeys.value.has(key)
}

function toggleSection(key: SectionKey) {
  const next = new Set(expandedKeys.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedKeys.value = next
  mountedKeys.value = new Set([...mountedKeys.value, key])
}

function previewLabels(section: SectionDefinition): string {
  return (sectionWidgets.value.get(section.key) ?? [])
    .slice(0, 2)
    .map((widget) => t(widget.labelKey))
    .join(' / ')
}

function previewSummary(section: SectionDefinition): string {
  const labels = previewLabels(section)
  const remainingCount = (sectionWidgets.value.get(section.key)?.length ?? 0) - 2
  return remainingCount > 0 ? `${labels} ${moreCount(remainingCount)}` : labels
}

function moreCount(count: number): string {
  return t('dashboard.personal_accordion.more_count', { count })
}

function badgeLabel(count: number): string {
  return count === 0
    ? t('dashboard.personal_accordion.empty')
    : t('dashboard.personal_accordion.count', { count })
}
</script>

<template>
  <div data-testid="scope-dashboard-accordion">
    <div
      v-if="widgets.length === 0"
      class="rounded-xl border border-dashed border-surface-400 py-12 text-center dark:border-surface-600"
    >
      <i class="pi pi-th-large mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">{{ t('dashboard.widget_settings.no_widgets_message') }}</p>
      <Button
        :label="t('dashboard.widget_settings.add_widget_button')"
        icon="pi pi-plus"
        text
        size="small"
        class="mt-2"
        @click="emit('configure')"
      />
    </div>

    <div v-else class="space-y-3">
      <section
        v-for="section in sections"
        :key="section.key"
        :data-section-key="section.key"
        class="rounded-xl border border-surface-200 dark:border-surface-700"
      >
        <button
          :id="`scope-dashboard-section-button-${section.key}`"
          type="button"
          class="flex min-h-11 w-full items-center gap-3 rounded-xl px-4 py-3 text-left hover:bg-surface-50 focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary dark:hover:bg-surface-800"
          :aria-expanded="isExpanded(section.key)"
          :aria-controls="`scope-dashboard-section-${section.key}`"
          @click="toggleSection(section.key)"
        >
          <i :class="section.icon" class="text-primary" aria-hidden="true" />
          <span class="min-w-0 flex-1">
            <span class="block font-semibold">{{ t(section.labelKey) }}</span>
            <span v-if="previewSummary(section)" class="block truncate text-xs text-surface-500">
              {{ previewSummary(section) }}
            </span>
          </span>
          <span
            class="min-w-5 rounded-full px-2 py-0.5 text-center text-xs font-bold tabular-nums"
            :class="
              (sectionWidgets.get(section.key)?.length ?? 0) === 0
                ? 'bg-surface-100 text-surface-400 dark:bg-surface-700'
                : 'bg-orange-100 text-orange-700 dark:bg-orange-950 dark:text-orange-300'
            "
            :data-widget-count="sectionWidgets.get(section.key)?.length ?? 0"
            :aria-label="
              t('dashboard.personal_accordion.visible_count', {
                count: sectionWidgets.get(section.key)?.length ?? 0,
              })
            "
            >{{ badgeLabel(sectionWidgets.get(section.key)?.length ?? 0) }}</span
          >
          <i
            class="pi pi-chevron-right text-xs text-surface-500 transition-transform motion-reduce:transition-none"
            :class="{ 'rotate-90': isExpanded(section.key) }"
            aria-hidden="true"
          />
        </button>

        <div
          :id="`scope-dashboard-section-${section.key}`"
          role="region"
          :aria-labelledby="`scope-dashboard-section-button-${section.key}`"
          :aria-hidden="!isExpanded(section.key)"
          :inert="!isExpanded(section.key)"
          class="grid transition-[grid-template-rows,opacity] duration-300 motion-reduce:transition-none"
          :class="
            isExpanded(section.key) ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'
          "
        >
          <div class="min-h-0 overflow-hidden">
            <div class="border-t border-surface-200 p-4 dark:border-surface-700">
              <template v-if="mountedKeys.has(section.key)">
                <slot
                  v-if="(sectionWidgets.get(section.key)?.length ?? 0) > 0"
                  :widgets="sectionWidgets.get(section.key) ?? []"
                />
                <div v-else class="py-4 text-center text-sm text-surface-500">
                  <p>{{ t('dashboard.widget_settings.no_widgets_message') }}</p>
                  <Button
                    :label="t('dashboard.widget_settings.add_widget_button')"
                    icon="pi pi-plus"
                    text
                    size="small"
                    class="mt-2"
                    @click="emit('configure')"
                  />
                </div>
              </template>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
