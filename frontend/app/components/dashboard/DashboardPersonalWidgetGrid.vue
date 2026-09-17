<script setup lang="ts">
import type { WidgetDefinition } from '~/composables/useDashboardWidgets'

defineProps<{
  widgets: WidgetDefinition[]
  collapsedKeys: Set<string>
}>()

const emit = defineEmits<{ 'toggle-collapse': [key: string] }>()
const dismissalHasContent = ref(false)

const dataWidgetKeys = new Set([
  'my-calendar',
  'timetable-today',
  'quick-memo',
  'event-dismissal-reminder',
  'notices',
  'upcoming-events',
  'return-stay-plan',
  'personal-todo',
  'weather',
  'todo-countdown',
  'reflection-today',
  'unread-threads',
  'team-announcements',
  'org-announcements',
  'my-blog',
  'my-teams',
  'my-organizations',
  'favorites',
  'my-timeline',
  'recent-activity',
  'recruitment-feed',
  'my-recruitments',
  'my-corkboard',
  'village-lobby-digest',
])

function isDataWidget(key: string): boolean {
  return dataWidgetKeys.has(key)
}

function linkTo(key: string): string | undefined {
  return {
    'event-dismissal-reminder': '/events',
    notices: '/notifications',
    'upcoming-events': '/calendar',
    'personal-todo': '/todos',
    weather: '/settings/profile',
    'todo-countdown': '/todos',
    'reflection-today': '/my/reflection',
    'unread-threads': '/chat',
    'team-announcements': '/notifications',
    'org-announcements': '/notifications',
    'my-blog': '/my/blog',
    'my-teams': '/teams',
    'my-organizations': '/organizations',
    favorites: '/my/favorites',
    'recent-activity': '/timeline',
  }[key]
}
</script>

<template>
  <template v-for="widget in widgets" :key="widget.key">
    <div
      v-show="widget.key !== 'event-dismissal-reminder' || dismissalHasContent"
      class="min-w-0"
      :class="
        widget.key === 'notices' || widget.key === 'my-calendar' ? 'col-span-1 md:col-span-2' : ''
      "
    >
      <template v-if="isDataWidget(widget.key)">
        <SectionCard v-if="widget.key === 'my-calendar'"><WidgetMyCalendar /></SectionCard>
        <DashboardTimetableTodayWidget v-else-if="widget.key === 'timetable-today'" />
        <DashboardQuickMemoWidget v-else-if="widget.key === 'quick-memo'" />
        <WidgetEventDismissalReminder
          v-else-if="widget.key === 'event-dismissal-reminder'"
          @has-content="dismissalHasContent = $event"
        />
        <WidgetNotices v-else-if="widget.key === 'notices'" />
        <WidgetUpcomingEvents v-else-if="widget.key === 'upcoming-events'" />
        <WidgetReturnStayPlan v-else-if="widget.key === 'return-stay-plan'" />
        <WidgetPersonalTodo v-else-if="widget.key === 'personal-todo'" />
        <WidgetWeather v-else-if="widget.key === 'weather'" />
        <WidgetTodoCountdown v-else-if="widget.key === 'todo-countdown'" />
        <WidgetReflectionToday v-else-if="widget.key === 'reflection-today'" />
        <WidgetUnreadThreads v-else-if="widget.key === 'unread-threads'" />
        <WidgetTeamAnnouncements v-else-if="widget.key === 'team-announcements'" />
        <WidgetOrgAnnouncements v-else-if="widget.key === 'org-announcements'" />
        <WidgetMyBlog v-else-if="widget.key === 'my-blog'" />
        <WidgetMyTeams v-else-if="widget.key === 'my-teams'" />
        <WidgetMyOrganizations v-else-if="widget.key === 'my-organizations'" />
        <WidgetFavorites v-else-if="widget.key === 'favorites'" />
        <WidgetMyTimeline v-else-if="widget.key === 'my-timeline'" />
        <WidgetRecentActivity v-else-if="widget.key === 'recent-activity'" />
        <WidgetRecruitmentFeed v-else-if="widget.key === 'recruitment-feed'" />
        <WidgetMyRecruitments v-else-if="widget.key === 'my-recruitments'" />
        <DashboardWidgetCard
          v-else-if="widget.key === 'my-corkboard'"
          :title="$t(widget.labelKey)"
          :icon="widget.icon"
          to="/my/corkboard"
          :scrollable="false"
          ><WidgetMyCorkboard
        /></DashboardWidgetCard>
        <DashboardWidgetCard
          v-else-if="widget.key === 'village-lobby-digest'"
          :title="$t(widget.labelKey)"
          :icon="widget.icon"
          to="/villages"
          :scrollable="false"
          ><WidgetVillageLobbyDigest
        /></DashboardWidgetCard>
      </template>
      <DashboardWidgetCard
        v-else
        title=""
        :scrollable="false"
        @click="linkTo(widget.key) && navigateTo(linkTo(widget.key)!)"
      >
        <div class="flex items-center gap-3" :class="collapsedKeys.has(widget.key) ? '' : 'mb-3'">
          <div
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
          >
            <i :class="widget.icon" class="text-xl" />
          </div>
          <NuxtLink v-if="linkTo(widget.key)" :to="linkTo(widget.key)" class="flex-1" @click.stop
            ><h3 class="text-[20px] font-semibold text-surface-700 dark:text-surface-200">
              {{ $t(widget.labelKey) }}
            </h3></NuxtLink
          >
          <h3
            v-else
            class="flex-1 text-[20px] font-semibold text-surface-700 dark:text-surface-200"
          >
            {{ $t(widget.labelKey) }}
          </h3>
          <button
            type="button"
            class="md:hidden flex items-center justify-center rounded-lg p-1.5 text-surface-400"
            :aria-label="$t(widget.labelKey)"
            @click.stop="emit('toggle-collapse', widget.key)"
          >
            <i
              class="pi text-sm"
              :class="collapsedKeys.has(widget.key) ? 'pi-chevron-down' : 'pi-chevron-up'"
            />
          </button>
          <i class="pi pi-chevron-right hidden text-xs text-surface-400 md:block" />
        </div>
        <p
          class="text-xs text-surface-500"
          :class="collapsedKeys.has(widget.key) ? 'hidden md:block' : ''"
        >
          {{ $t(widget.descriptionKey) }}
        </p>
      </DashboardWidgetCard>
    </div>
  </template>
</template>
