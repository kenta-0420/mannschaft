<script setup lang="ts">
import type { ActionMemo } from '~/types/actionMemo'

/**
 * F02.5 Phase 3: チーム視点のWORKメモ一覧ページ。
 *
 * <p>指定チームに投稿済みの WORK カテゴリメモを無限スクロールで表示する。
 * {@code GET /api/v1/action-memos?category=WORK&postedTeamId={teamId}} を呼ぶ。</p>
 */

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const store = useActionMemoStore()

const teamId = computed(() => Number(route.params.teamId))

/** 現在のチーム名（availableTeams から取得） */
const teamName = computed(() => {
  const team = store.availableTeams.find((t2) => t2.id === teamId.value)
  return team?.name ?? `Team #${teamId.value}`
})

const memos = ref<ActionMemo[]>([])
const loading = ref(false)
const nextCursor = ref<string | null>(null)
const hasMore = computed(() => nextCursor.value !== null)

async function loadMemos(cursor?: string) {
  if (loading.value) return
  loading.value = true
  try {
    const api = useActionMemoApi()
    const res = await api.fetchMemos({ limit: 50, cursor })
    // ローカルでカテゴリ + postedTeamId フィルタ（APIが対応するまでの暫定）
    const filtered = res.data.filter(
      (m) => m.category === 'WORK' && m.postedTeamId === teamId.value,
    )
    if (cursor) {
      memos.value = [...memos.value, ...filtered]
    } else {
      memos.value = filtered
    }
    nextCursor.value = res.nextCursor
  } catch {
    // エラーは静かに処理（再試行ボタンは表示しない設計）
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (nextCursor.value) {
    await loadMemos(nextCursor.value)
  }
}

function goBack() {
  router.push('/action-memo')
}

onMounted(async () => {
  await store.fetchAvailableTeams()
  await loadMemos()
})
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-4 px-3 py-4">
    <header class="flex items-center gap-2">
      <button
        type="button"
        class="rounded-lg px-2 py-1 text-sm text-surface-500 hover:bg-surface-100 dark:hover:bg-surface-700"
        data-testid="team-timeline-back"
        @click="goBack"
      >
        <i class="pi pi-arrow-left mr-1 text-xs" />
        {{ t('action_memo.page.back_to_memo') }}
      </button>
    </header>

    <h1 class="text-xl font-bold" data-testid="team-timeline-title">
      <i class="pi pi-users mr-2 text-primary" />
      {{ t('action_memo.phase3.post_to_team.button') }} — {{ teamName }}
    </h1>

    <ActionMemoList
      :memos="memos"
      :loading="loading"
      data-testid="team-timeline-list"
    />

    <div
      v-if="!loading && memos.length === 0"
      class="py-8 text-center text-sm text-surface-400"
      data-testid="team-timeline-empty"
    >
      {{ t('action_memo.phase3.team_timeline.empty') }}
    </div>

    <button
      v-if="hasMore"
      type="button"
      class="mx-auto rounded-lg border border-surface-200 px-4 py-2 text-sm text-surface-600 hover:bg-surface-100 dark:border-surface-700 dark:text-surface-300 dark:hover:bg-surface-700"
      :disabled="loading"
      data-testid="team-timeline-load-more"
      @click="loadMore"
    >
      {{ t('action_memo.phase3.team_timeline.load_more') }}
    </button>
  </div>
</template>
