<script setup lang="ts">
import type { ActionMemo, MemoAuditLog, Mood } from '~/types/actionMemo'

/**
 * F02.5 メモ1件を表示するカード。
 *
 * <p>content / 投稿時刻 / mood バッジ / タグ / 編集削除ボタンを含む。
 * mood は 5 色のバッジで表示する。</p>
 *
 * <p><b>Phase 4-α</b>: 組織スコープバッジ + 監査ログ折りたたみ追加。</p>
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
  const m = props.memo.createdAt.match(/T(\d{2}:\d{2})/)
  return m ? m[1] : ''
})

const moodInfo = computed<MoodMeta | null>(() => {
  if (!props.memo.mood) return null
  return moodMeta[props.memo.mood]
})

// ─── Phase 4-α: 監査ログ折りたたみ ───────────────────────────
const auditOpen = ref(false)
const auditLogs = ref<MemoAuditLog[]>([])
const auditLoading = ref(false)
const auditError = ref(false)

async function toggleAudit() {
  auditOpen.value = !auditOpen.value
  if (auditOpen.value && auditLogs.value.length === 0 && !auditError.value) {
    await fetchAuditLogs()
  }
}

async function fetchAuditLogs() {
  auditLoading.value = true
  auditError.value = false
  try {
    const res = await $fetch<{ data: MemoAuditLog[] }>(
      `/api/v1/action-memos/${props.memo.id}/audit-logs`,
    )
    auditLogs.value = res.data ?? []
  }
  catch {
    auditError.value = true
  }
  finally {
    auditLoading.value = false
  }
}

function formatAuditTime(iso: string): string {
  if (!iso) return ''
  const m = iso.match(/T(\d{2}:\d{2})/)
  return m ? (m[1] ?? iso) : iso
}
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
        <!-- Phase 4-α: 組織スコープバッジ -->
        <span
          v-if="memo.organizationId"
          class="inline-flex items-center gap-1 rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-700 dark:bg-violet-900/40 dark:text-violet-300"
          data-testid="action-memo-card-org-badge"
        >
          <i class="pi pi-building text-xs" />
          <span>{{ memo.orgVisibility === 'ORG_WIDE' ? t('action_memo.phase4.org_scope.org_wide') : t('action_memo.phase4.org_scope.team_only') }}</span>
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

    <!-- Phase 4-α: 監査ログ折りたたみ -->
    <div class="border-t border-surface-100 pt-1 dark:border-surface-700">
      <button
        type="button"
        class="flex items-center gap-1 text-xs text-surface-400 hover:text-surface-600 dark:text-surface-500 dark:hover:text-surface-300"
        data-testid="action-memo-card-audit-toggle"
        @click="toggleAudit"
      >
        <i class="pi pi-history text-xs" />
        <span>{{ t('action_memo.phase4.audit.toggle') }}</span>
        <i class="pi text-xs" :class="auditOpen ? 'pi-chevron-up' : 'pi-chevron-down'" />
      </button>

      <div v-if="auditOpen" class="mt-2 space-y-1" data-testid="action-memo-card-audit-panel">
        <div v-if="auditLoading" class="text-xs text-surface-400">
          {{ t('action_memo.phase4.audit.loading') }}
        </div>
        <div v-else-if="auditError" class="text-xs text-rose-500">
          {{ t('action_memo.phase4.audit.error') }}
        </div>
        <div v-else-if="auditLogs.length === 0" class="text-xs text-surface-400">
          {{ t('action_memo.phase4.audit.empty') }}
        </div>
        <div
          v-for="log in auditLogs"
          v-else
          :key="log.id"
          class="flex items-center gap-2 text-xs text-surface-500 dark:text-surface-400"
        >
          <span class="shrink-0 font-mono">{{ formatAuditTime(log.createdAt) }}</span>
          <span>{{ log.eventType }}</span>
        </div>
      </div>
    </div>
  </article>
</template>
