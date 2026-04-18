<script setup lang="ts">
import type { ActionMemo, ActionMemoCategory } from '~/types/actionMemo'

/**
 * F02.5 行動メモ メイン画面（ワンショット入力 + 当日メモ一覧）。
 *
 * <p>設計書 §4.x の最頻アクセスページ。マウント時に当日メモと設定を取得する。
 * Phase 2 で編集ダイアログ・オフラインバナー・終業画面への導線を追加。
 * Phase 3 でカテゴリ・実績時間・進捗率・チーム投稿フィールドを追加。</p>
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

/**
 * 直近7日間の日付範囲（mood-stats 取得用）。
 */
function sevenDaysAgo(): string {
  const now = new Date()
  const jst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  jst.setDate(jst.getDate() - 6)
  return jst.toISOString().slice(0, 10)
}

// === 編集ダイアログ ===
const editDialogOpen = ref(false)
const editingMemo = ref<ActionMemo | null>(null)

// === Phase 3: 追加フィールド（折りたたみパネル用）===
const phase3PanelOpen = ref(false)
const selectedCategory = ref<ActionMemoCategory>(store.settings.defaultCategory ?? 'OTHER')
const selectedDuration = ref<number | null>(null)
const selectedProgressRate = ref<number | null>(null)
const selectedCompletesTodo = ref(false)
const selectedTeamId = ref<number | null>(store.settings.defaultPostTeamId ?? null)

// 設定のデフォルト値を反映
watch(
  () => store.settings,
  (s) => {
    selectedCategory.value = s.defaultCategory ?? 'OTHER'
    if (selectedTeamId.value === null) {
      selectedTeamId.value = s.defaultPostTeamId ?? null
    }
  },
)

// ActionMemoInput に渡す phase3 props（createMemo 呼び出し時に使用）
// index.vue はシンプルな入力欄（ActionMemoInput）を流用しつつ、
// phase3 フィールドは専用の折りたたみパネルで管理する。

// === オフライン同期 ===
function handleOnline() {
  // online 復帰時に自動 flush を試みる
  void store.flushOfflineQueue()
}

onMounted(async () => {
  await Promise.all([
    store.fetchSettings(),
    store.fetchMemosForDate(today.value),
    store.fetchAvailableTeams(),
  ])
  await store.refreshOfflineQueueCount()
  // mood_enabled = true の場合のみ mood-stats を取得
  if (store.isMoodEnabled) {
    await store.fetchMoodStats(sevenDaysAgo(), today.value)
  }
  if (typeof window !== 'undefined') {
    window.addEventListener('online', handleOnline)
  }
})

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('online', handleOnline)
  }
})

async function onDelete(memo: ActionMemo) {
  await store.deleteMemo(memo.id)
}

function onEdit(memo: ActionMemo) {
  editingMemo.value = memo
  editDialogOpen.value = true
}

function onSaved(_memo: ActionMemo) {
  // store.updateMemo が memos を更新済みなので UI は同期されている
  editDialogOpen.value = false
  editingMemo.value = null
}

async function onManualSync() {
  await store.flushOfflineQueue()
}

function goSettings() {
  router.push('/action-memo/settings')
}

function goClosing() {
  router.push('/action-memo/closing')
}

function goWeekly() {
  router.push('/action-memo/weekly')
}

function goTags() {
  router.push('/action-memo/tags')
}
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-4 px-3 py-4">
    <header class="flex items-center justify-between">
      <h1 class="text-xl font-bold">{{ t('action_memo.title') }}</h1>
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="rounded-lg px-3 py-1 text-sm text-primary hover:bg-primary/10"
          data-testid="action-memo-tags-link"
          @click="goTags"
        >
          <i class="pi pi-tag mr-1 text-xs" />
          {{ t('action_memo.page.tags_link') }}
        </button>
        <button
          type="button"
          class="rounded-lg px-3 py-1 text-sm text-primary hover:bg-primary/10"
          data-testid="action-memo-weekly-link"
          @click="goWeekly"
        >
          <i class="pi pi-calendar mr-1 text-xs" />
          {{ t('action_memo.page.weekly_link') }}
        </button>
        <button
          type="button"
          class="rounded-lg px-3 py-1 text-sm text-primary hover:bg-primary/10"
          data-testid="action-memo-closing-link"
          @click="goClosing"
        >
          <i class="pi pi-flag mr-1 text-xs" />
          {{ t('action_memo.page.closing_link') }}
        </button>
        <button
          type="button"
          class="rounded-lg px-3 py-1 text-sm text-primary hover:bg-primary/10"
          data-testid="action-memo-settings-link"
          @click="goSettings"
        >
          <i class="pi pi-cog mr-1 text-xs" />
          {{ t('action_memo.page.settings_link') }}
        </button>
      </div>
    </header>

    <div
      v-if="store.isOffline || store.offlineQueueCount > 0"
      class="flex items-center justify-between gap-3 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-900/30 dark:text-amber-200"
      role="status"
      data-testid="action-memo-offline-banner"
    >
      <div class="flex-1">
        <p>{{ t('action_memo.offline.banner') }}</p>
        <p v-if="store.offlineQueueCount > 0" class="text-xs opacity-80">
          {{ t('action_memo.offline.queued', { count: store.offlineQueueCount }) }}
        </p>
      </div>
      <button
        type="button"
        class="rounded px-2 py-1 text-xs font-medium text-amber-800 underline hover:bg-amber-100 dark:text-amber-200 dark:hover:bg-amber-800/40"
        data-testid="action-memo-offline-sync"
        @click="onManualSync"
      >
        {{ t('action_memo.offline.sync_button') }}
      </button>
    </div>

    <div
      v-if="store.error"
      class="rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-900/30 dark:text-rose-200"
      role="alert"
      data-testid="action-memo-error-banner"
    >
      {{ t(store.error) }}
    </div>

    <ActionMemoInput />

    <!-- Phase 3: カテゴリ選択（常時表示） -->
    <div class="flex items-center gap-2 px-1">
      <span class="text-xs text-surface-500 dark:text-surface-400">
        {{ t('action_memo.phase3.category.label') }}:
      </span>
      <CategorySelector
        v-model="selectedCategory"
        data-testid="index-category-selector"
      />
    </div>

    <!-- Phase 3: 追加フィールド（折りたたみ） -->
    <details
      :open="phase3PanelOpen"
      class="rounded-xl border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-800"
      data-testid="phase3-details-panel"
      @toggle="phase3PanelOpen = ($event.target as HTMLDetailsElement).open"
    >
      <summary
        class="cursor-pointer select-none px-3 py-2 text-xs font-medium text-surface-500 hover:text-primary dark:text-surface-400"
        data-testid="phase3-details-toggle"
      >
        <i class="pi pi-chevron-right mr-1 text-xs transition-transform" :class="phase3PanelOpen ? 'rotate-90' : ''" />
        {{ t('action_memo.phase3.advanced_fields') }}
      </summary>
      <div class="flex flex-col gap-3 border-t border-surface-200 px-3 py-3 dark:border-surface-700">
        <DurationInput
          v-model="selectedDuration"
          data-testid="index-duration-input"
        />
        <ProgressRateSlider
          v-model="selectedProgressRate"
          :related-todo-id="null"
          data-testid="index-progress-rate-slider"
        />
        <TodoCompleteCheckbox
          v-model="selectedCompletesTodo"
          :related-todo-id="null"
          data-testid="index-todo-complete-checkbox"
        />
        <TeamPostSwitch
          v-model="selectedTeamId"
          :category="selectedCategory"
          :available-teams="store.availableTeams"
          data-testid="index-team-post-switch"
        />
      </div>
    </details>

    <!-- 気分集計（mood_enabled = true の場合のみ表示） -->
    <MoodChart
      v-if="store.isMoodEnabled && store.moodStats && store.moodStats.total > 0"
      :stats="store.moodStats"
    />

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

    <ActionMemoEditDialog
      v-model="editDialogOpen"
      :memo="editingMemo"
      @saved="onSaved"
    />
  </div>
</template>
