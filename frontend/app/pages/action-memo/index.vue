<script setup lang="ts">
import dayjs from 'dayjs'
import type { ActionMemo, ActionMemoCategory, OrgVisibility } from '~/types/actionMemo'

/**
 * F02.5 行動メモ メイン画面（ワンショット入力 + 選択日メモ一覧）。
 *
 * <p>設計書 §4.x の最頻アクセスページ。マウント時に当日メモと設定を取得する。
 * AC-21: 日付ピッカーで過去の日のメモも参照できる。
 * AC-24: PageHeader + 使い方ガイドモーダル。</p>
 */

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const store = useActionMemoStore()
const authStore = useAuthStore()

// 選択中の日付（Date型 — PrimeVue DatePicker とのバインド用）
const selectedDate = ref<Date>(new Date())

// 文字列形式の選択日（API/表示に使用）
const selectedDateStr = computed(() =>
  dayjs(selectedDate.value).tz(authStore.user?.timezone ?? 'Asia/Tokyo').format('YYYY-MM-DD'),
)

const todayStr = computed(() =>
  dayjs().tz(authStore.user?.timezone ?? 'Asia/Tokyo').format('YYYY-MM-DD'),
)

const isToday = computed(() => selectedDateStr.value === todayStr.value)

const selectedMemos = computed(() => store.currentDayMemos(selectedDateStr.value))

// 日付変更時に対象日のメモを取得
watch(selectedDate, async () => {
  await store.fetchMemosForDate(selectedDateStr.value)
})

/**
 * 直近7日間の日付範囲（mood-stats 取得用）。
 */
function sevenDaysAgo(): string {
  return dayjs().tz(authStore.user?.timezone ?? 'Asia/Tokyo').subtract(6, 'day').format('YYYY-MM-DD')
}

// === 使い方ガイド ===
const showGuide = ref(false)

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

// Phase 4-α: 組織スコープ
const selectedOrgId = ref<number | null>(null)
const selectedOrgVisibility = ref<OrgVisibility>('TEAM_ONLY')

watch([selectedOrgId, selectedOrgVisibility], ([orgId, orgVis]) => {
  store.setPendingOrgScope(orgId, orgId ? orgVis : null)
})

function onOrgChange() {
  if (!selectedOrgId.value) {
    selectedOrgVisibility.value = 'TEAM_ONLY'
  }
}

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

// === オフライン同期 ===
function handleOnline() {
  void store.flushOfflineQueue()
}

onMounted(async () => {
  // 通知からのディープリンク: ?date=YYYY-MM-DD があれば選択日を上書きして当日メモへ直遷移
  const queryDate = route.query.date
  if (typeof queryDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(queryDate)) {
    selectedDate.value = dayjs(queryDate).toDate()
  }

  await Promise.all([
    store.fetchSettings(),
    store.fetchMemosForDate(selectedDateStr.value),
    store.fetchAvailableTeams(),
    store.fetchAvailableOrgs(),
  ])
  await store.refreshOfflineQueueCount()
  // mood_enabled = true の場合のみ mood-stats を取得
  if (store.isMoodEnabled) {
    await store.fetchMoodStats(sevenDaysAgo(), todayStr.value)
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

function backToToday() {
  selectedDate.value = new Date()
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
  <div class="mx-auto max-w-2xl">
    <PageHeader
      :title="t('action_memo.title')"
      :back="false"
      help
      @help="showGuide = true"
    >
      <template #actions>
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
      </template>
    </PageHeader>

    <div class="flex flex-col gap-4 px-3 pb-4">
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

          <!-- Phase 5-2: 組織スコープ選択 -->
          <div
            v-if="store.availableOrgs.length > 0"
            class="flex flex-col gap-1"
            data-testid="index-org-scope-selector"
          >
            <label class="text-xs text-surface-600 dark:text-surface-400">
              {{ t('action_memo.phase4.org_scope.label') }}
            </label>
            <div class="flex items-center gap-2">
              <select
                v-model.number="selectedOrgId"
                class="flex-1 rounded-md border border-surface-300 bg-surface-0 px-2 py-1 text-xs dark:border-surface-600 dark:bg-surface-800"
                data-testid="org-scope-select"
                @change="onOrgChange"
              >
                <option :value="null">—</option>
                <option v-for="org in store.availableOrgs" :key="org.id" :value="org.id">
                  {{ org.name }}
                </option>
              </select>
              <select
                v-if="selectedOrgId"
                v-model="selectedOrgVisibility"
                class="rounded-md border border-surface-300 bg-surface-0 px-2 py-1 text-xs dark:border-surface-600 dark:bg-surface-800"
                data-testid="org-visibility-select"
              >
                <option value="TEAM_ONLY">{{ t('action_memo.phase4.org_scope.team_only') }}</option>
                <option value="ORG_WIDE">{{ t('action_memo.phase4.org_scope.org_wide') }}</option>
              </select>
            </div>
          </div>
        </div>
      </details>

      <!-- 気分集計（mood_enabled = true の場合のみ表示） -->
      <MoodChart
        v-if="store.isMoodEnabled && store.moodStats && store.moodStats.total > 0"
        :stats="store.moodStats"
      />

      <!-- AC-21: 日付選択 -->
      <div class="flex items-center gap-3 px-1">
        <span class="text-xs font-medium text-surface-500 dark:text-surface-400">
          {{ t('action_memo.page.date_picker_label') }}:
        </span>
        <DatePicker
          v-model="selectedDate"
          date-format="yy-mm-dd"
          :max-date="new Date()"
          show-icon
          class="text-sm"
          data-testid="action-memo-date-picker"
        />
        <button
          v-if="!isToday"
          type="button"
          class="rounded-lg px-2 py-1 text-xs text-primary hover:bg-primary/10"
          data-testid="action-memo-back-to-today"
          @click="backToToday"
        >
          <i class="pi pi-home mr-1 text-xs" />
          {{ t('action_memo.page.back_to_today') }}
        </button>
      </div>

      <section class="flex flex-col gap-2">
        <h2 class="px-1 text-sm font-semibold text-surface-700 dark:text-surface-200">
          <template v-if="isToday">
            {{ t('action_memo.page.today_memos') }}
          </template>
          <template v-else>
            {{ t('action_memo.page.selected_date_memos', { date: selectedDateStr }) }}
          </template>
        </h2>
        <ActionMemoList
          :memos="selectedMemos"
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

    <!-- 使い方ガイドモーダル -->
    <ActionMemoGuideModal v-model:visible="showGuide" />
  </div>
</template>
