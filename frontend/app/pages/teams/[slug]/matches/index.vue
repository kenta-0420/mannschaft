<script setup lang="ts">
// F08.10 単独試合一覧（04_frontend_and_ux.md §G.1）。
// kind/status フィルタ・試合カード・進行中バッジ・FAB「＋試合を記録」・空状態・ページング。
import type {
  MatchSummaryResponse,
  MatchKind,
  MatchSummaryStatus,
  ListMatchesParams,
} from '~/types/match'
import { MATCH_KINDS, MATCH_SUMMARY_STATUSES } from '~/types/match'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const teamSlug = String(route.params.slug)
const { t } = useI18n()

const { listMatches } = useMatchApi()

// === 組織／チーム 数値 ID の解決（slug→数値 orgId/teamId・useMatchOrgContext に集約）===
const { resolveContext } = useMatchOrgContext()
const orgId = ref<number | null>(null)
const teamId = ref<number | null>(null)

async function loadOrganizationId(): Promise<void> {
  const ctx = await resolveContext(teamSlug)
  orgId.value = ctx?.orgId ?? null
  teamId.value = ctx?.teamId ?? null
}

// === フィルタ状態 ===
const kindFilter = ref<MatchKind | null>(null)
const statusFilter = ref<MatchSummaryStatus | null>(null)

const kindOptions = computed(() =>
  MATCH_KINDS.map((k) => ({ value: k, label: t(`match.kind.${k}`) })),
)
const statusOptions = computed(() =>
  MATCH_SUMMARY_STATUSES.map((s) => ({ value: s, label: t(`match.status.${s}`) })),
)

// === 一覧・ページング状態 ===
const matches = ref<MatchSummaryResponse[]>([])
const loading = ref(false)
const page = ref(0)
const size = 20
const total = ref(0)

const hasMore = computed(() => matches.value.length < total.value)

async function load(reset = true): Promise<void> {
  if (orgId.value === null || teamId.value === null) return
  if (reset) {
    page.value = 0
    matches.value = []
  }
  loading.value = true
  try {
    const params: ListMatchesParams = {
      page: page.value,
      size,
    }
    if (kindFilter.value) params.kind = kindFilter.value
    if (statusFilter.value) params.status = statusFilter.value
    const res = await listMatches(orgId.value, teamId.value, params)
    const rows = res.data ?? []
    matches.value = reset ? rows : [...matches.value, ...rows]
    total.value = res.meta?.total ?? matches.value.length
  } catch {
    // エラーは composable 内で通知済み
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  page.value += 1
  await load(false)
}

// フィルタ変更で再取得
watch([kindFilter, statusFilter], () => {
  void load(true)
})

// === カード選択時の遷移（進行中・それ以外も live に合流＝§G.1a-2） ===
// 遷移先 pages/teams/[id]/matches/[matchId]/live.vue は 3-B で実装済み。
function onSelectMatch(match: MatchSummaryResponse): void {
  if (!match.id) return
  void router.push(`/teams/${teamSlug}/matches/${match.id}/live`)
}

// === FAB から作成ページへ ===
function goToCreate(): void {
  void router.push(`/teams/${teamSlug}/matches/new`)
}

onMounted(async () => {
  await loadOrganizationId()
  await load(true)
})
</script>

<template>
  <div class="mx-auto max-w-3xl pb-24">
    <div class="mb-1 flex items-center gap-3">
      <BackButton :to="`/teams/${teamSlug}`" />
      <PageHeader :title="$t('match.list.title')" size="sm" />
    </div>
    <p class="mb-4 text-sm text-surface-500">{{ $t('match.list.subtitle') }}</p>

    <!-- フィルタ -->
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <div class="flex flex-col gap-1">
        <label class="text-xs text-surface-500">{{ $t('match.list.filter.kind') }}</label>
        <Select
          v-model="kindFilter"
          :options="kindOptions"
          option-label="label"
          option-value="value"
          show-clear
          :placeholder="$t('match.list.filter.all')"
          class="w-40"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-xs text-surface-500">{{ $t('match.list.filter.status') }}</label>
        <Select
          v-model="statusFilter"
          :options="statusOptions"
          option-label="label"
          option-value="value"
          show-clear
          :placeholder="$t('match.list.filter.all')"
          class="w-40"
        />
      </div>
    </div>

    <PageLoading v-if="loading && matches.length === 0" size="40px" />

    <template v-else>
      <div v-if="matches.length > 0" class="grid gap-3 sm:grid-cols-2">
        <MatchCard
          v-for="m in matches"
          :key="m.id"
          :match="m"
          @select="onSelectMatch"
        />
      </div>

      <DashboardEmptyState
        v-else
        icon="pi pi-flag"
        :message="$t('match.list.empty')"
      />

      <div v-if="hasMore" class="mt-4 flex justify-center">
        <Button
          :label="$t('match.list.load_more')"
          :loading="loading"
          text
          @click="loadMore"
        />
      </div>
    </template>

    <!-- FAB「＋試合を記録」（入口 2・§G.1a-2） -->
    <button
      type="button"
      class="fixed bottom-6 right-6 flex min-h-[2.75rem] items-center gap-2 rounded-full bg-primary px-5 py-3 text-sm font-semibold text-primary-contrast shadow-lg transition hover:brightness-95"
      :aria-label="$t('match.list.record_button')"
      @click="goToCreate"
    >
      <i class="pi pi-plus" />
      <span>{{ $t('match.list.record_button') }}</span>
    </button>
  </div>
</template>
