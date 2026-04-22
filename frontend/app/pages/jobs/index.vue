<script setup lang="ts">
import type {
  JobPagedMeta,
  JobPostingSummaryResponse,
} from '~/types/jobmatching'

/**
 * F13.1 Worker 向け求人検索ページ。
 *
 * <p>自分が所属するチームの中から 1 チームを選択し、公開中（OPEN）の求人を表示する。
 * 所属チーム横断の検索 API は MVP では存在しないため、チームセレクタ + そのチームの求人一覧という形を取る。
 * 後続 Phase で第三版（JOBBER_INTERNAL / JOBBER_PUBLIC_BOARD）に対応した横断検索を追加予定。</p>
 *
 * <p>チームに所属していない場合は空状態を表示する。</p>
 */

const router = useRouter()
const { t } = useI18n()
const api = useJobPostingApi()
const { error } = useNotification()
const teamStore = useTeamStore()

const PAGE_SIZE = 20

const selectedTeamId = ref<number | null>(null)
const jobs = ref<JobPostingSummaryResponse[]>([])
const meta = ref<JobPagedMeta>({ total: 0, page: 0, size: PAGE_SIZE, totalPages: 0 })
const loading = ref(false)
const currentPage = ref(0)

const teamOptions = computed(() =>
  teamStore.myTeams.map(t => ({
    label: t.nickname1 || t.name,
    value: t.id,
  })),
)

async function load(page = 0) {
  if (selectedTeamId.value == null) {
    jobs.value = []
    meta.value = { total: 0, page: 0, size: PAGE_SIZE, totalPages: 0 }
    return
  }
  loading.value = true
  currentPage.value = page
  try {
    const res = await api.searchJobs({
      teamId: selectedTeamId.value,
      status: 'OPEN',
      page,
      size: PAGE_SIZE,
    })
    jobs.value = res.data
    meta.value = res.meta
  }
  catch (e) {
    error(t('jobmatching.error.loadFailed'), String(e))
    jobs.value = []
    meta.value = { total: 0, page: 0, size: PAGE_SIZE, totalPages: 0 }
  }
  finally {
    loading.value = false
  }
}

function onTeamChange() {
  load(0)
}

function onPageChange(event: { page: number }) {
  load(event.page)
}

function goToDetail(jobId: number) {
  router.push(`/jobs/${jobId}`)
}

onMounted(async () => {
  if (teamStore.myTeams.length === 0) {
    await teamStore.fetchMyTeams()
  }
  // デフォルトで最初のチームを選択
  if (teamStore.myTeams.length > 0) {
    selectedTeamId.value = teamStore.myTeams[0]!.id
    await load(0)
  }
})
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <div class="mb-4">
      <h1 class="text-2xl font-bold">
        {{ t('jobmatching.workerSearch.title') }}
      </h1>
      <p class="mt-1 text-sm text-surface-500">
        {{ t('jobmatching.workerSearch.description') }}
      </p>
    </div>

    <!-- チーム選択 -->
    <div class="mb-4">
      <label
        class="mb-1 block text-sm font-medium"
        for="team-select"
      >
        {{ t('jobmatching.workerSearch.selectTeam') }}
      </label>
      <Select
        id="team-select"
        v-model="selectedTeamId"
        :options="teamOptions"
        option-label="label"
        option-value="value"
        :placeholder="t('jobmatching.workerSearch.teamPlaceholder')"
        class="w-full sm:w-80"
        @change="onTeamChange"
      />
    </div>

    <!-- 所属チーム無し -->
    <div
      v-if="teamStore.myTeams.length === 0"
      class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
    >
      <i class="pi pi-users mb-2 block text-3xl" />
      <p>{{ t('jobmatching.workerSearch.noTeams') }}</p>
    </div>

    <!-- ローディング -->
    <div
      v-else-if="loading"
      class="flex justify-center p-8"
    >
      <ProgressSpinner />
    </div>

    <!-- 空 -->
    <div
      v-else-if="jobs.length === 0"
      class="rounded border border-dashed border-surface-300 p-8 text-center text-surface-500 dark:border-surface-600"
    >
      <i class="pi pi-briefcase mb-2 block text-3xl" />
      <p>{{ t('jobmatching.workerSearch.empty') }}</p>
    </div>

    <!-- 一覧 -->
    <div
      v-else
      class="flex flex-col gap-3"
    >
      <div
        v-for="job in jobs"
        :key="job.id"
        class="cursor-pointer"
        @click="goToDetail(job.id)"
      >
        <JobPostingCard :job="job" />
      </div>
    </div>

    <!-- ページネーション -->
    <div
      v-if="meta.totalPages > 1"
      class="mt-6 flex justify-center"
    >
      <Paginator
        :rows="PAGE_SIZE"
        :total-records="meta.total"
        :first="currentPage * PAGE_SIZE"
        @page="onPageChange"
      />
    </div>
  </div>
</template>
