<script setup lang="ts">
import type { WidgetDefinition } from '~/composables/useDashboardWidgets'

type SectionKey = 'schedule' | 'todo' | 'communication' | 'feed' | 'content-affiliation'
interface SectionDefinition {
  key: SectionKey
  labelKey: string
  icon: string
  widgetKeys: readonly string[]
}
const props = defineProps<{ widgets: WidgetDefinition[]; collapsedKeys: Set<string> }>()
const emit = defineEmits<{ 'toggle-collapse': [key: string]; configure: [] }>()
const { t } = useI18n()
const sections: readonly SectionDefinition[] = [
  {
    key: 'schedule',
    labelKey: 'dashboard.personal_accordion.schedule',
    icon: '📆',
    widgetKeys: [
      'upcoming-events',
      'my-recruitments',
      'return-stay-plan',
      'weather',
      'timetable-today',
    ],
  },
  {
    key: 'todo',
    labelKey: 'dashboard.personal_accordion.todo',
    icon: '✅',
    widgetKeys: ['personal-todo', 'reflection-today', 'todo-countdown', 'event-dismissal-reminder'],
  },
  {
    key: 'communication',
    labelKey: 'dashboard.personal_accordion.communication',
    icon: '💬',
    widgetKeys: ['unread-threads', 'team-announcements', 'org-announcements'],
  },
  {
    key: 'feed',
    labelKey: 'dashboard.personal_accordion.feed',
    icon: '📰',
    widgetKeys: ['my-timeline', 'recruitment-feed', 'village-lobby-digest', 'recent-activity'],
  },
  {
    key: 'content-affiliation',
    labelKey: 'dashboard.personal_accordion.content_affiliation',
    icon: '🗂️',
    widgetKeys: [
      'quick-memo',
      'my-blog',
      'my-corkboard',
      'favorites',
      'my-teams',
      'my-organizations',
    ],
  },
]
const nowWidgets = computed(() =>
  props.widgets.filter((widget) => widget.key === 'my-calendar' || widget.key === 'notices'),
)
const sectionWidgets = computed(
  () =>
    new Map(
      sections.map((section) => [
        section.key,
        props.widgets.filter((widget) => section.widgetKeys.includes(widget.key)),
      ]),
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
  <div data-testid="personal-dashboard-accordion">
    <DashboardPersonalWidgetGrid
      v-if="nowWidgets.length > 0"
      :widgets="nowWidgets"
      :collapsed-keys="collapsedKeys"
      class="mb-4 grid grid-cols-[repeat(auto-fit,minmax(230px,1fr))] gap-4"
      @toggle-collapse="emit('toggle-collapse', $event)"
    />
    <div class="space-y-3">
      <section
        v-for="section in sections"
        :key="section.key"
        class="rounded-xl border border-surface-200 dark:border-surface-700"
      >
        <button
          :id="`personal-dashboard-section-button-${section.key}`"
          type="button"
          class="flex min-h-11 w-full items-center gap-3 rounded-xl px-4 py-3 text-left hover:bg-surface-50 focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary dark:hover:bg-surface-800"
          :aria-expanded="isExpanded(section.key)"
          :aria-controls="`personal-dashboard-section-${section.key}`"
          @click="toggleSection(section.key)"
        >
          <span aria-hidden="true">{{ section.icon }}</span>
          <span class="min-w-0 flex-1">
            <span class="block font-semibold">{{ t(section.labelKey) }}</span>
            <span v-if="previewLabels(section)" class="block truncate text-xs text-surface-500"
              >{{ previewLabels(section)
              }}<template v-if="(sectionWidgets.get(section.key)?.length ?? 0) > 2">
                {{ moreCount((sectionWidgets.get(section.key)?.length ?? 0) - 2) }}</template
              ></span
            >
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
            class="pi pi-chevron-right text-xs text-surface-500 transition-transform"
            :class="{ 'rotate-90': isExpanded(section.key) }"
            aria-hidden="true"
          />
        </button>
        <div
          v-show="isExpanded(section.key)"
          :id="`personal-dashboard-section-${section.key}`"
          role="region"
          :aria-labelledby="`personal-dashboard-section-button-${section.key}`"
          class="border-t border-surface-200 p-4 dark:border-surface-700"
        >
          <template v-if="mountedKeys.has(section.key)">
            <DashboardPersonalWidgetGrid
              v-if="(sectionWidgets.get(section.key)?.length ?? 0) > 0"
              :widgets="sectionWidgets.get(section.key) ?? []"
              :collapsed-keys="collapsedKeys"
              class="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-4"
              @toggle-collapse="emit('toggle-collapse', $event)"
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
      </section>
    </div>
  </div>
</template>
