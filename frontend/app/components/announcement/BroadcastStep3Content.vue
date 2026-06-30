<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'
import type {
  BroadcastPriority,
  BulletinThreadContent,
  BlogPostContent,
  TodoContent,
  ScheduleContent,
  SurveyContent,
  WizardFormState,
} from '~/types/announcement_broadcast'

const props = defineProps<{
  modelValue: WizardFormState
  scopeType: AnnouncementScopeType
  scopeId: string
  broadcasting: boolean
  /** ADMIN以上かどうか（優先度選択の表示制御） */
  isAdmin?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: WizardFormState]
  back: []
  submit: []
}>()

const { t } = useI18n()

/**
 * Date を ISO-8601 LocalDateTime 形式 (YYYY-MM-DDTHH:mm:ss) に変換する。
 *
 * <p>BE の {@code expiresAt}（リクエスト直下）と SURVEY の {@code closesAt} は
 * {@code LocalDateTime}（タイムゾーンオフセット非対応）なので、{@code toISOString()} の
 * 末尾 {@code Z}＋ミリ秒を送ると JavaTimeModule に弾かれ 400 になる。
 * ユーザーが選んだローカルの壁時計時刻をオフセットなしでそのまま送る。
 * 一方 SCHEDULE の startAt/endAt は OffsetDateTime なので {@code toISOString()} のままで良い。</p>
 */
function toLocalDateTimeString(d: Date): string {
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}T${hh}:${mi}:${ss}`
}

/** 共通タイトルフィールド（チャネルをまたいで引き継ぐ） */
const title = computed({
  get() {
    const c = props.modelValue.content as Partial<BulletinThreadContent & BlogPostContent & TodoContent>
    return c.title ?? ''
  },
  set(value: string) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, title: value },
    })
  },
})

/** 共通本文フィールド */
const body = computed({
  get() {
    const c = props.modelValue.content as Partial<BulletinThreadContent & BlogPostContent>
    return c.body ?? ''
  },
  set(value: string) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, body: value },
    })
  },
})

const priority = computed({
  get() {
    return props.modelValue.priority
  },
  set(value: BroadcastPriority) {
    emit('update:modelValue', { ...props.modelValue, priority: value })
  },
})

const expiresAt = computed({
  get() {
    return props.modelValue.expiresAt ? new Date(props.modelValue.expiresAt) : null
  },
  set(value: Date | null) {
    // expiresAt は BE で LocalDateTime（オフセット非対応）。Z 付き ISO は 400 になる。
    emit('update:modelValue', {
      ...props.modelValue,
      expiresAt: value ? toLocalDateTimeString(value) : null,
    })
  },
})

// ---- SCHEDULE チャネル用 computed ----

/** SCHEDULE: 終日フラグ */
const scheduleAllDay = computed({
  get() {
    const c = props.modelValue.content as Partial<ScheduleContent>
    return c.allDay ?? false
  },
  set(value: boolean) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, allDay: value },
    })
  },
})

/** SCHEDULE: 開始日時（Date ↔ ISO 8601 変換） */
const scheduleStartAt = computed({
  get() {
    const c = props.modelValue.content as Partial<ScheduleContent>
    return c.startAt ? new Date(c.startAt) : null
  },
  set(value: Date | null) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, startAt: value ? value.toISOString() : null },
    })
  },
})

/** SCHEDULE: 終了日時（任意・Date ↔ ISO 8601 変換） */
const scheduleEndAt = computed({
  get() {
    const c = props.modelValue.content as Partial<ScheduleContent>
    return c.endAt ? new Date(c.endAt) : null
  },
  set(value: Date | null) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, endAt: value ? value.toISOString() : null },
    })
  },
})

/** SCHEDULE: 説明（任意・最大5000文字） */
const scheduleDescription = computed({
  get() {
    const c = props.modelValue.content as Partial<ScheduleContent>
    return typeof c.description === 'string' ? c.description : ''
  },
  set(value: string) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, description: value || null },
    })
  },
})

/** SCHEDULE: 場所（任意・最大300文字） */
const scheduleLocation = computed({
  get() {
    const c = props.modelValue.content as Partial<ScheduleContent>
    return c.location ?? ''
  },
  set(value: string) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, location: value || null },
    })
  },
})

// ---- SURVEY チャネル用 computed ----

/** SURVEY: 説明（任意・最大5000文字） */
const surveyDescription = computed({
  get() {
    const c = props.modelValue.content as Partial<SurveyContent>
    return c.description ?? ''
  },
  set(value: string) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, description: value || null },
    })
  },
})

/** SURVEY: 締切日時（任意・Date ↔ ISO 8601 変換） */
const surveyClosesAt = computed({
  get() {
    const c = props.modelValue.content as Partial<SurveyContent>
    return c.closesAt ? new Date(c.closesAt) : null
  },
  set(value: Date | null) {
    // closesAt は BE で LocalDateTime（オフセット非対応）。Z 付き ISO は 400 になる。
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, closesAt: value ? toLocalDateTimeString(value) : null },
    })
  },
})

const priorityOptions: { label: string; value: BroadcastPriority }[] = [
  { label: t('announcement.priority.NORMAL'), value: 'NORMAL' },
  { label: t('announcement.priority.IMPORTANT'), value: 'IMPORTANT' },
  { label: t('announcement.priority.URGENT'), value: 'URGENT' },
]

const channel = computed(() => props.modelValue.selectedChannel)

/** バリデーション: 最低限のフィールドが入力されているか */
const canSubmit = computed(() => {
  if (!channel.value) return false
  const c = props.modelValue.content as Record<string, unknown>
  if (channel.value === 'TIMELINE_POST') {
    return typeof c.body === 'string' && c.body.trim().length > 0
  }
  if (channel.value === 'SCHEDULE') {
    // タイトルと開始日時が必須
    const hasTitle = typeof c.title === 'string' && (c.title as string).trim().length > 0
    const hasStartAt = typeof c.startAt === 'string' && (c.startAt as string).length > 0
    return hasTitle && hasStartAt
  }
  return typeof c.title === 'string' && (c.title as string).trim().length > 0
})
</script>

<template>
  <div class="flex flex-col gap-5">
    <!-- チャネル別フォーム -->
    <template v-if="channel === 'BULLETIN_THREAD' || channel === 'BLOG_POST'">
      <!-- タイトル -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_title') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText v-model="title" class="w-full" :max-length="200" />
      </div>
      <!-- 本文 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_body') }}
          <span class="text-red-500">*</span>
        </label>
        <Textarea v-model="body" rows="5" class="w-full resize-none" />
      </div>
    </template>

    <template v-else-if="channel === 'TIMELINE_POST'">
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_body') }}
          <span class="text-red-500">*</span>
        </label>
        <Textarea v-model="body" rows="5" class="w-full resize-none" />
      </div>
    </template>

    <template v-else-if="channel === 'TODO'">
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_title') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText v-model="title" class="w-full" :max-length="200" />
      </div>
      <!-- TODOの補足テキスト -->
      <p class="text-xs text-surface-400 dark:text-surface-500">
        {{ $t('announcement.todo_assignee_hint') }}
      </p>
    </template>

    <template v-else-if="channel === 'SCHEDULE'">
      <!-- タイトル -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_title') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText v-model="title" class="w-full" :max-length="200" />
      </div>
      <!-- 終日トグル -->
      <div class="flex items-center gap-2">
        <Checkbox v-model="scheduleAllDay" :binary="true" input-id="schedule-all-day" />
        <label for="schedule-all-day" class="text-sm font-medium text-surface-700 dark:text-surface-300 cursor-pointer">
          {{ $t('announcement.form_all_day') }}
        </label>
      </div>
      <!-- 開始日時（必須） -->
      <div v-if="!scheduleAllDay" class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_start_at') }}
          <span class="text-red-500">*</span>
        </label>
        <DatePicker
          v-model="scheduleStartAt"
          :show-time="true"
          :show-button-bar="true"
          date-format="yy/mm/dd"
          hour-format="24"
          class="w-full"
        />
      </div>
      <!-- 終日の場合: 開始日時（日付のみ） -->
      <div v-else class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_start_at') }}
          <span class="text-red-500">*</span>
        </label>
        <DatePicker
          v-model="scheduleStartAt"
          :show-time="false"
          :show-button-bar="true"
          date-format="yy/mm/dd"
          class="w-full"
        />
      </div>
      <!-- 終了日時（任意） -->
      <div v-if="!scheduleAllDay" class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_end_at') }}
        </label>
        <DatePicker
          v-model="scheduleEndAt"
          :show-time="true"
          :show-button-bar="true"
          date-format="yy/mm/dd"
          hour-format="24"
          class="w-full"
        />
      </div>
      <!-- 説明（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_description') }}
        </label>
        <Textarea v-model="scheduleDescription" rows="3" class="w-full resize-none" :maxlength="5000" />
      </div>
      <!-- 場所（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_location') }}
        </label>
        <InputText v-model="scheduleLocation" class="w-full" :max-length="300" />
      </div>
    </template>

    <template v-else-if="channel === 'SURVEY'">
      <!-- タイトル -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_title') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText v-model="title" class="w-full" :max-length="200" />
      </div>
      <!-- 説明（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_description') }}
        </label>
        <Textarea v-model="surveyDescription" rows="4" class="w-full resize-none" :maxlength="5000" />
      </div>
      <!-- 締切日時（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_closes_at') }}
        </label>
        <DatePicker
          v-model="surveyClosesAt"
          :show-time="true"
          :show-button-bar="true"
          date-format="yy/mm/dd"
          hour-format="24"
          class="w-full"
          :min-date="new Date()"
        />
      </div>
      <!-- ヒントテキスト -->
      <p class="text-xs text-surface-400 dark:text-surface-500">
        {{ $t('announcement.survey_questions_hint') }}
      </p>
    </template>

    <!-- 優先度（ADMIN以上のみ全選択肢を表示） -->
    <div v-if="isAdmin" class="flex flex-col gap-1">
      <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
        {{ $t('announcement.priority_label') }}
      </label>
      <Select
        v-model="priority"
        :options="priorityOptions"
        option-label="label"
        option-value="value"
        class="w-full"
      />
    </div>

    <!-- 表示期限（任意） -->
    <div class="flex flex-col gap-1">
      <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
        {{ $t('announcement.expires_at_label') }}
      </label>
      <DatePicker
        v-model="expiresAt"
        :show-time="true"
        :show-button-bar="true"
        date-format="yy/mm/dd"
        class="w-full"
        :min-date="new Date()"
      />
    </div>

    <!-- ナビゲーション -->
    <div class="flex justify-between pt-2">
      <Button :label="$t('button.back')" icon="pi pi-arrow-left" severity="secondary" text @click="emit('back')" />
      <Button
        :label="$t('announcement.submit_button')"
        icon="pi pi-send"
        icon-pos="right"
        :loading="broadcasting"
        :disabled="!canSubmit || broadcasting"
        @click="emit('submit')"
      />
    </div>
  </div>
</template>
