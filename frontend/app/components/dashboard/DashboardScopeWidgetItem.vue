<script setup lang="ts">
import dayjs from 'dayjs'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useDatetime } from '~/composables/useDatetime'
import type { ScopeWidgetPreviewTarget } from '~/composables/useScopeWidgetPreview'
import type {
  ScopeUpcomingEventItem,
  ScopeBlogPostItem,
  ScopeChatChannelItem,
  ScopeTodoItem,
  ScopeCalendarSummary,
  ScopeBulletinThreadItem,
} from '~/types/dashboard-scope'

const props = defineProps<{
  kind: ScopeWidgetPreviewTarget['kind']
  item:
    | ScopeUpcomingEventItem
    | ScopeBlogPostItem
    | ScopeChatChannelItem
    | ScopeTodoItem
    | ScopeCalendarSummary
    | ScopeBulletinThreadItem
}>()
const emit = defineEmits<{ select: [target: ScopeWidgetPreviewTarget, trigger: HTMLElement] }>()
const { t } = useI18n()
const { formatDate, formatDateTime } = useDatetime()
const event = computed(() =>
  props.kind === 'schedule' ? (props.item as ScopeUpcomingEventItem) : null,
)
const bulletin = computed(() =>
  props.kind === 'bulletin' ? (props.item as ScopeBulletinThreadItem) : null,
)
const blog = computed(() => (props.kind === 'blog' ? (props.item as ScopeBlogPostItem) : null))
const chat = computed(() => (props.kind === 'chat' ? (props.item as ScopeChatChannelItem) : null))
const todo = computed(() => (props.kind === 'todo' ? (props.item as ScopeTodoItem) : null))
const calendar = computed(() =>
  props.kind === 'calendar' ? (props.item as ScopeCalendarSummary) : null,
)
const title = computed(() =>
  'title' in props.item ? props.item.title : (chat.value?.name ?? t('swipeWidgets.calendar')),
)
const target = computed<ScopeWidgetPreviewTarget>(() => ({
  kind: props.kind,
  title: title.value,
  id: 'id' in props.item ? props.item.id : undefined,
  slug: blog.value?.slug,
  calendar: calendar.value ?? undefined,
}))
const testid = computed(
  () => `scope-widget-${props.kind}${target.value.id === undefined ? '' : `-${target.value.id}`}`,
)
const overdue = computed(
  () => !!todo.value?.due_date && dayjs(todo.value.due_date).isBefore(dayjs(), 'day'),
)
function select(event: MouseEvent): void {
  emit('select', target.value, event.currentTarget as HTMLElement)
}
</script>

<template>
  <button
    type="button"
    class="flex min-h-11 w-full min-w-0 items-center gap-3 rounded-lg border-2 border-surface-300 bg-surface-50 p-3 text-left hover:border-primary focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary dark:border-surface-600 dark:bg-surface-700/50"
    :data-testid="testid"
    aria-haspopup="dialog"
    @click="select"
  >
    <span
      v-if="bulletin && !bulletin.is_read"
      class="h-2 w-2 shrink-0 rounded-full bg-primary"
      :aria-label="t('swipeWidgets.list.unread')"
    />
    <span class="min-w-0 flex-1">
      <template v-if="calendar">
        <span class="block text-xs text-surface-500">{{ t('swipeWidgets.list.nextEvent') }}</span>
        <span class="block truncate text-sm font-medium">{{
          calendar.next_event ?? t('swipeWidgets.list.noNextEvent')
        }}</span>
        <span class="mt-3 block text-sm text-surface-600 dark:text-surface-300">
          <i class="pi pi-calendar mr-2 text-primary" />{{
            t('swipeWidgets.list.eventsThisWeek', { count: calendar.events_this_week })
          }}
        </span>
      </template>
      <template v-else>
        <span class="block truncate text-sm font-medium">
          <i v-if="chat" class="pi pi-hashtag mr-1 text-surface-400" />{{ title }}
        </span>
        <template v-if="event">
          <span class="block text-xs text-surface-500"
            ><i class="pi pi-clock mr-1" />{{
              event.all_day ? formatDate(event.start_at) : formatDateTime(event.start_at)
            }}</span
          >
          <span v-if="event.location" class="block truncate text-xs text-surface-400"
            ><i class="pi pi-map-marker mr-1" />{{ event.location }}</span
          >
        </template>
        <span v-if="bulletin" class="block text-xs text-surface-500"
          ><i class="pi pi-clock mr-1" />{{ formatDateTime(bulletin.updated_at) }}</span
        >
        <span v-if="blog" class="mt-0.5 flex flex-wrap gap-x-3 text-xs text-surface-500">
          <span v-if="blog.author"><i class="pi pi-user mr-1" />{{ blog.author }}</span>
          <span v-if="blog.published_at"
            ><i class="pi pi-calendar mr-1" />{{ formatDate(blog.published_at) }}</span
          >
        </span>
        <span
          v-if="chat?.last_message_preview"
          class="mt-0.5 block line-clamp-1 text-xs text-surface-500"
          >{{ chat.last_message_preview }}</span
        >
        <span v-if="todo?.due_date" class="block text-xs text-surface-500"
          ><i class="pi pi-clock mr-1" />{{ formatDate(todo.due_date) }}</span
        >
      </template>
    </span>
    <Tag
      v-if="event?.all_day"
      :value="t('swipeWidgets.list.allDay')"
      severity="secondary"
      rounded
    />
    <Tag
      v-if="bulletin && !bulletin.is_read"
      :value="t('swipeWidgets.list.unread')"
      severity="danger"
      rounded
    />
    <Tag v-if="overdue" :value="t('swipeWidgets.list.overdue')" severity="danger" rounded />
    <Badge v-if="chat && chat.unread_count > 0" :value="chat.unread_count" severity="danger" />
  </button>
</template>
