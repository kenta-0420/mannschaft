<script setup lang="ts">
import type { ActionMemo } from '~/types/actionMemo'

/**
 * F02.5 行動メモ メイン画面（ワンショット入力 + 当日メモ一覧）。
 *
 * <p>設計書 §4.x の最頻アクセスページ。マウント時に当日メモと設定を取得する。</p>
 */

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const { t } = useI18n()
const store = useActionMemoStore()

/** JST の今日（YYYY-MM-DD） */
function todayJst(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  return jst.toISOString().slice(0, 10)
}

const today = ref(todayJst())

const todaysMemos = computed(() => store.currentDayMemos(today.value))

onMounted(async () => {
  await Promise.all([store.fetchSettings(), store.fetchMemosForDate(today.value)])
})

async function onDelete(memo: ActionMemo) {
  await store.deleteMemo(memo.id)
}

function onEdit(_memo: ActionMemo) {
  // Phase 1 では編集ダイアログ未実装。Phase 2 で対応する想定。
  // クリック自体は無視して fall-through。
}

function goSettings() {
  router.push('/action-memo/settings')
}
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-4 px-3 py-4">
    <header class="flex items-center justify-between">
      <h1 class="text-xl font-bold">{{ t('action_memo.title') }}</h1>
      <button
        type="button"
        class="rounded-lg px-3 py-1 text-sm text-primary hover:bg-primary/10"
        data-testid="action-memo-settings-link"
        @click="goSettings"
      >
        <i class="pi pi-cog mr-1 text-xs" />
        {{ t('action_memo.page.settings_link') }}
      </button>
    </header>

    <div
      v-if="store.error"
      class="rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-900/30 dark:text-rose-200"
      role="alert"
      data-testid="action-memo-error-banner"
    >
      {{ t(store.error) }}
    </div>

    <ActionMemoInput />

    <section class="flex flex-col gap-2">
      <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('action_memo.page.today_memos') }}
      </h2>
      <ActionMemoList
        :memos="todaysMemos"
        :loading="store.loading"
        @edit="onEdit"
        @delete="onDelete"
      />
    </section>
  </div>
</template>
