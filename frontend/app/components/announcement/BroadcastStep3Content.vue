<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'
import type {
  BroadcastPriority,
  BulletinThreadContent,
  TimelinePostContent,
  BlogPostContent,
  TodoContent,
  WizardFormState,
} from '~/types/announcement_broadcast'

const props = defineProps<{
  modelValue: WizardFormState
  scopeType: AnnouncementScopeType
  scopeId: number
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

/** タイムラインのcontentフィールド */
const timelineContent = computed({
  get() {
    const c = props.modelValue.content as Partial<TimelinePostContent>
    return c.content ?? ''
  },
  set(value: string) {
    emit('update:modelValue', {
      ...props.modelValue,
      content: { ...props.modelValue.content, content: value },
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
    emit('update:modelValue', {
      ...props.modelValue,
      expiresAt: value ? value.toISOString() : null,
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
    return typeof c.content === 'string' && c.content.trim().length > 0
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
        <Textarea v-model="timelineContent" rows="5" class="w-full resize-none" />
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
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_title') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText v-model="title" class="w-full" :max-length="200" />
      </div>
      <!-- TODO: start_at / end_at フィールドは Phase 2 で追加予定 -->
    </template>

    <template v-else-if="channel === 'SURVEY'">
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ $t('announcement.form_title') }}
          <span class="text-red-500">*</span>
        </label>
        <InputText v-model="title" class="w-full" :max-length="200" />
      </div>
      <!-- TODO: アンケート設問フィールドは Phase 2 で追加予定 -->
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
