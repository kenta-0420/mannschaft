<script setup lang="ts">
/**
 * F02.5 Phase 4-β: 管理職向け行動メモダッシュボード。
 *
 * <p>チームの ADMIN または DEPUTY_ADMIN が、メンバーの WORK カテゴリメモを閲覧する画面。
 * {@link useActionMemoDashboard} でカーソルページネーションを管理する。</p>
 *
 * <p>Phase 6-1: メンバー選択をテキスト入力からドロップダウン（select）に変更。
 * チーム選択時に {@code GET /api/v1/teams/{teamId}/members} でメンバー一覧を取得する。</p>
 */

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const store = useActionMemoStore()
const { fetchTeamMembers } = useActionMemoApi()
const { memos, nextCursor, loading, loadingMore, error, loadMemos, loadMore, reset } =
  useActionMemoDashboard()

const selectedTeamId = ref<number | null>(null)
const selectedMemberId = ref<number | null>(null)
const memberId = ref<number | null>(null)
const teamMembers = ref<{ userId: number; displayName: string }[]>([])
const membersLoading = ref<boolean>(false)

const hasMore = computed(() => nextCursor.value !== null)

onMounted(async () => {
  await store.fetchAvailableTeams()
})

async function onTeamChange(id: number | null) {
  selectedTeamId.value = id
  selectedMemberId.value = null
  memberId.value = null
  teamMembers.value = []
  reset()
  if (id) {
    membersLoading.value = true
    try {
      teamMembers.value = await fetchTeamMembers(id)
    } finally {
      membersLoading.value = false
    }
  }
}

async function onSearch() {
  const tid = selectedTeamId.value
  const mid = selectedMemberId.value
  if (!tid || !mid) return
  memberId.value = mid
  await loadMemos(tid, mid)
}

async function onLoadMore() {
  if (!selectedTeamId.value || !memberId.value) return
  await loadMore(selectedTeamId.value, memberId.value)
}
</script>

<template>
  <div data-testid="dashboard-page" class="flex flex-col gap-4 p-4">
    <div class="flex items-center gap-2">
      <NuxtLink to="/action-memo" class="text-surface-500 hover:text-surface-700">
        ←
      </NuxtLink>
      <h1 class="text-lg font-bold">{{ t('action_memo.dashboard.title') }}</h1>
    </div>

    <!-- チーム選択 + メンバーID入力 -->
    <section
      class="flex flex-col gap-3 rounded-2xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
    >
      <div class="flex flex-col gap-2">
        <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
          {{ t('action_memo.dashboard.select_team') }}
        </label>
        <select
          :value="selectedTeamId ?? ''"
          class="rounded-lg border border-surface-300 bg-surface-0 p-2 text-sm dark:border-surface-600 dark:bg-surface-800"
          data-testid="dashboard-team-select"
          @change="onTeamChange(($event.target as HTMLSelectElement).value ? Number(($event.target as HTMLSelectElement).value) : null)"
        >
          <option value="">—</option>
          <option
            v-for="team in store.availableTeams"
            :key="team.id"
            :value="team.id"
          >
            {{ team.name }}
          </option>
        </select>
      </div>

      <div class="flex gap-2">
        <div class="flex flex-1 flex-col gap-1">
          <label class="text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('action_memo.dashboard.select_member') }}
          </label>
          <select
            :value="selectedMemberId ?? ''"
            :disabled="!selectedTeamId || membersLoading"
            class="rounded-lg border border-surface-300 bg-surface-0 p-2 text-sm disabled:opacity-50 dark:border-surface-600 dark:bg-surface-800"
            data-testid="dashboard-member-input"
            @change="selectedMemberId = ($event.target as HTMLSelectElement).value ? Number(($event.target as HTMLSelectElement).value) : null"
          >
            <option value="">{{ membersLoading ? '…' : '—' }}</option>
            <option
              v-for="member in teamMembers"
              :key="member.userId"
              :value="member.userId"
            >
              {{ member.displayName }}
            </option>
          </select>
        </div>
        <div class="flex items-end">
          <button
            :disabled="!selectedTeamId || !selectedMemberId"
            class="rounded-lg bg-primary-500 px-4 py-2 text-sm font-medium text-white disabled:opacity-40"
            data-testid="dashboard-search-btn"
            @click="onSearch"
          >
            {{ t('action_memo.input.submit') }}
          </button>
        </div>
      </div>
    </section>

    <!-- エラー -->
    <div
      v-if="error"
      class="rounded-xl border border-red-300 bg-red-50 p-3 text-sm text-red-700 dark:border-red-700 dark:bg-red-900/20 dark:text-red-400"
      data-testid="dashboard-error"
    >
      {{ t(error) }}
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary-500 border-t-transparent" />
    </div>

    <!-- メモ一覧 -->
    <template v-else-if="memos.length > 0">
      <div class="flex flex-col gap-2">
        <div
          v-for="memo in memos"
          :key="memo.id"
          class="rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-800"
          data-testid="dashboard-memo-item"
        >
          <div class="flex items-start justify-between gap-2">
            <p class="flex-1 text-sm text-surface-800 dark:text-surface-100">
              {{ memo.content }}
            </p>
            <span class="shrink-0 text-xs text-surface-500">
              {{ memo.memoDate }}
            </span>
          </div>
          <div v-if="memo.tags && memo.tags.length > 0" class="mt-1 flex flex-wrap gap-1">
            <span
              v-for="tag in memo.tags"
              :key="tag.id"
              class="rounded-full bg-surface-100 px-2 py-0.5 text-xs text-surface-600 dark:bg-surface-700 dark:text-surface-300"
            >
              {{ tag.name }}
            </span>
          </div>
        </div>
      </div>

      <!-- もっと読み込む -->
      <button
        v-if="hasMore"
        :disabled="loadingMore"
        class="w-full rounded-xl border border-surface-300 py-2 text-sm text-surface-600 disabled:opacity-40 dark:border-surface-600 dark:text-surface-400"
        data-testid="dashboard-load-more-btn"
        @click="onLoadMore"
      >
        {{ loadingMore ? '…' : t('action_memo.dashboard.load_more') }}
      </button>
    </template>

    <!-- データなし -->
    <div
      v-else-if="memberId && !loading"
      class="py-8 text-center text-sm text-surface-500"
      data-testid="dashboard-no-data"
    >
      {{ t('action_memo.dashboard.no_work_memos') }}
    </div>
  </div>
</template>
