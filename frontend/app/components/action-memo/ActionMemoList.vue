<script setup lang="ts">
import type { ActionMemo } from '~/types/actionMemo'

/**
 * F02.5 メモカードのリスト。
 *
 * <p>日付ごとにグルーピングして表示する。空状態には空メッセージを出す。</p>
 */

const props = defineProps<{
  memos: ActionMemo[]
  loading?: boolean
}>()

const emit = defineEmits<{
  edit: [memo: ActionMemo]
  delete: [memo: ActionMemo]
}>()

const { t } = useI18n()

interface MemoGroup {
  date: string
  memos: ActionMemo[]
}

/**
 * memo_date でグルーピング。新しい日付が先頭、各グループ内も新しい順。
 */
const groups = computed<MemoGroup[]>(() => {
  const map = new Map<string, ActionMemo[]>()
  for (const memo of props.memos) {
    const arr = map.get(memo.memoDate) ?? []
    arr.push(memo)
    map.set(memo.memoDate, arr)
  }
  const sortedDates = Array.from(map.keys()).sort((a, b) => (a < b ? 1 : -1))
  return sortedDates.map((date) => ({
    date,
    memos: (map.get(date) ?? []).slice().sort((a, b) => {
      // createdAt 降順（新しい順）
      if (a.createdAt === b.createdAt) return b.id - a.id
      return a.createdAt < b.createdAt ? 1 : -1
    }),
  }))
})

const isEmpty = computed(() => !props.loading && props.memos.length === 0)
</script>

<template>
  <div data-testid="action-memo-list" class="flex flex-col gap-4">
    <div
      v-if="isEmpty"
      class="rounded-xl border border-dashed border-surface-300 p-6 text-center text-sm text-surface-500 dark:border-surface-600 dark:text-surface-400"
      data-testid="action-memo-list-empty"
    >
      {{ t('action_memo.page.empty') }}
    </div>

    <div v-for="group in groups" :key="group.date" class="flex flex-col gap-2">
      <h3
        class="px-1 text-xs font-semibold uppercase tracking-wide text-surface-500 dark:text-surface-400"
      >
        {{ group.date }}
      </h3>
      <div class="flex flex-col gap-2">
        <ActionMemoCard
          v-for="memo in group.memos"
          :key="memo.id"
          :memo="memo"
          @edit="emit('edit', $event)"
          @delete="emit('delete', $event)"
        />
      </div>
    </div>
  </div>
</template>
