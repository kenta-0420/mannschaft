<script setup lang="ts">
// NuxtLink は #components から明示 import して `<component :is>` にコンポーネント実体を渡す
// （`:is="'NuxtLink'"` という文字列は死んだリンクになる既知の罠。pages/villages/[id]/admin/index.vue 参照）。
import { NuxtLink } from '#components'

interface DetailField {
  field: string
  before?: string | null
  after?: string | null
  changed?: boolean
}

const props = withDefaults(defineProps<{
  activityType: string
  actorName: string
  actorAvatarUrl: string | null
  targetTitle: string
  scopeName: string
  createdAt: string
  /** §3.2 detail.fields 相当。既存7種別（detail = null）は空配列＝3行目は表示されない */
  detailFields?: DetailField[]
  /** SCHEDULE 系のみタップで対象予定へ遷移させるための対象種別（§2.3・§8.3） */
  targetType?: string | null
  /** 遷移先の組み立てに使う対象 ID（`/calendar?scheduleId=` 用。既存の通知リンク遷移と同じ経路に倣う） */
  targetId?: number | null
}>(), {
  detailFields: () => [],
  targetType: null,
  targetId: null,
})

const { t, te } = useI18n()

const relativeTime = useRelativeTime(toRef(props, 'createdAt'))

// 表示に関与しないアイコンのみ保持する（verb は i18n 化・§8 是正）。日本語文言のハードコードは撤去。
const activityIcons: Record<string, string> = {
  POST_CREATED: 'pi pi-pencil',
  EVENT_CREATED: 'pi pi-calendar-plus',
  MEMBER_JOINED: 'pi pi-user-plus',
  TODO_COMPLETED: 'pi pi-check-circle',
  BULLETIN_CREATED: 'pi pi-megaphone',
  POLL_CREATED: 'pi pi-chart-bar',
  FILE_UPLOADED: 'pi pi-upload',
  SCHEDULE_CREATED: 'pi pi-calendar-plus',
  SCHEDULE_UPDATED: 'pi pi-calendar',
  SCHEDULE_CANCELLED: 'pi pi-calendar-times',
  SCHEDULE_RESCHEDULED: 'pi pi-calendar',
}

const icon = computed(() => activityIcons[props.activityType] ?? 'pi pi-circle')

/** camelCase（フィールド名）→ snake_case 変換（i18n キー組み立て用） */
function toSnakeCase(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase()
}

const verb = computed(() => {
  const key = `dashboard.activity_feed.${props.activityType.toLowerCase()}`
  return te(key) ? t(key) : t('dashboard.activity_feed.unknown')
})

/** §3.2「未知フィールド値へのフォールバック」: 未知語彙でも握りつぶさずフィールド名をそのまま表示する */
function fieldLabel(field: string): string {
  const key = `dashboard.activity_feed.detail_${toSnakeCase(field)}`
  return te(key) ? t(key) : field
}

// §8.3 C-1: 3行目に表示する差分は先頭 N=3 件のみ（fields 自体の上限10件は BE 側・§3.2）
const DETAIL_FIELDS_DISPLAY_LIMIT = 3
const visibleDetailFields = computed(() => props.detailFields.slice(0, DETAIL_FIELDS_DISPLAY_LIMIT))

// AC-30: SCHEDULE 系のみタップで対象予定へ遷移する。既存7種別は targetType が SCHEDULE でないため遷移しない。
// 遷移先は既存の通知リンク遷移（/calendar?scheduleId=<id>）と同じ経路に倣う（calendar.vue の
// openLinkedScheduleFromQuery が既に対応済み。teams/[slug] 配下の数値ID誤用バグ(CMP-054)を踏まない）。
const isNavigable = computed(() => props.targetType === 'SCHEDULE' && props.targetId != null)
const scheduleLink = computed(() => (isNavigable.value ? `/calendar?scheduleId=${props.targetId}` : undefined))
</script>

<template>
  <component
    :is="isNavigable ? NuxtLink : 'div'"
    :to="scheduleLink"
    class="flex items-start gap-3 py-2"
    :class="{ 'cursor-pointer hover:bg-surface-100 dark:hover:bg-surface-800': isNavigable }"
  >
    <Avatar
      :image="actorAvatarUrl ?? undefined"
      :label="actorAvatarUrl ? undefined : actorName.charAt(0)"
      shape="circle"
      size="normal"
    />
    <div class="min-w-0 flex-1">
      <p class="text-sm">
        <span class="font-medium">{{ actorName }}</span>
        <span class="text-surface-500">{{ verb }}</span>
      </p>
      <p class="truncate text-sm text-surface-600 dark:text-surface-400">
        <i :class="icon" class="mr-1 text-xs" />
        {{ targetTitle }}
      </p>
      <p
        v-for="(detailField, index) in visibleDetailFields"
        :key="index"
        class="truncate text-xs text-surface-500 dark:text-surface-400"
      >
        <template v-if="detailField.changed">
          {{ t('dashboard.activity_feed.detail_description_changed') }}
        </template>
        <template v-else>
          {{ fieldLabel(detailField.field) }}: {{ detailField.before }} → {{ detailField.after }}
        </template>
      </p>
      <div class="mt-1 flex items-center gap-2 text-xs text-surface-400">
        <span>{{ scopeName }}</span>
        <span>·</span>
        <span>{{ relativeTime }}</span>
      </div>
    </div>
  </component>
</template>
