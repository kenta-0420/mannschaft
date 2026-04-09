<script setup lang="ts">
import type { ActionMemo, Mood } from '~/types/actionMemo'

/**
 * F02.5 メモ1件を表示するカード。
 *
 * <p>content / 投稿時刻 / mood バッジ / タグ / 編集削除ボタンを含む。
 * mood は 5 色のバッジで表示する。</p>
 */

const props = defineProps<{
  memo: ActionMemo
}>()

const emit = defineEmits<{
  edit: [memo: ActionMemo]
  delete: [memo: ActionMemo]
}>()

const { t } = useI18n()

interface MoodMeta {
  emoji: string
  i18nKey: string
  badgeClass: string
}

const moodMeta: Record<Mood, MoodMeta> = {
  GREAT: {
    emoji: '😄',
    i18nKey: 'action_memo.mood.great',
    badgeClass: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300',
  },
  GOOD: {
    emoji: '🙂',
    i18nKey: 'action_memo.mood.good',
    badgeClass: 'bg-teal-100 text-teal-700 dark:bg-teal-900/40 dark:text-teal-300',
  },
  OK: {
    emoji: '😐',
    i18nKey: 'action_memo.mood.ok',
    badgeClass: 'bg-surface-100 text-surface-700 dark:bg-surface-700 dark:text-surface-200',
  },
  TIRED: {
    emoji: '😩',
    i18nKey: 'action_memo.mood.tired',
    badgeClass: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300',
  },
  BAD: {
    emoji: '😞',
    i18nKey: 'action_memo.mood.bad',
    badgeClass: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300',
  },
}

/** 時刻だけを HH:MM 形式で表示 */
const displayTime = computed(() => {
  if (!props.memo.createdAt) return ''
  // ISO は "2026-04-09T08:32:14" 形式（タイムゾーンなし）
  const m = props.memo.createdAt.match(/T(\d{2}:\d{2})/)
  return m ? m[1] : ''
})

const moodInfo = computed<MoodMeta | null>(() => {
  if (!props.memo.mood) return null
  return moodMeta[props.memo.mood]
})
</script>

<template>
  <article
    class="group flex flex-col gap-2 rounded-xl border border-surface-200 bg-surface-0 p-3 transition-shadow hover:shadow-sm dark:border-surface-700 dark:bg-surface-800"
    data-testid="action-memo-card"
  >
    <div class="flex items-start justify-between gap-2">
      <div class="flex items-center gap-2 text-xs text-surface-500 dark:text-surface-400">
        <span>{{ displayTime }}</span>
        <span
          v-if="moodInfo"
          class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs"
          :class="moodInfo.badgeClass"
          data-testid="action-memo-card-mood"
        >
          <span>{{ moodInfo.emoji }}</span>
          <span>{{ t(moodInfo.i18nKey) }}</span>
        </span>
        <span
          v-if="memo.relatedTodoId"
          class="rounded-full bg-blue-100 px-2 py-0.5 text-xs text-blue-700 dark:bg-blue-900/40 dark:text-blue-200"
        >
          {{ t('action_memo.card.linked_todo') }} #{{ memo.relatedTodoId }}
        </span>
      </div>
      <div class="flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
        <button
          type="button"
          class="rounded p-1 text-surface-400 hover:bg-surface-100 hover:text-surface-700 dark:hover:bg-surface-700"
          :title="t('action_memo.card.edit')"
          data-testid="action-memo-card-edit"
          @click="emit('edit', memo)"
        >
          <i class="pi pi-pencil text-xs" />
        </button>
        <button
          type="button"
          class="rounded p-1 text-surface-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-900/40"
          :title="t('action_memo.card.delete')"
          data-testid="action-memo-card-delete"
          @click="emit('delete', memo)"
        >
          <i class="pi pi-trash text-xs" />
        </button>
      </div>
    </div>

    <p class="whitespace-pre-wrap break-words text-sm text-surface-800 dark:text-surface-100">
      {{ memo.content }}
    </p>

    <div v-if="memo.tags && memo.tags.length > 0" class="flex flex-wrap gap-1">
      <span
        v-for="tag in memo.tags"
        :key="tag.id"
        class="inline-flex items-center rounded-full px-2 py-0.5 text-xs"
        :class="
          tag.deleted
            ? 'bg-surface-100 text-surface-400 line-through dark:bg-surface-800'
            : 'bg-surface-100 text-surface-700 dark:bg-surface-700 dark:text-surface-200'
        "
        :style="!tag.deleted && tag.color ? { backgroundColor: tag.color, color: '#fff' } : {}"
      >
        #{{ tag.name }}
      </span>
    </div>
  </article>
</template>
