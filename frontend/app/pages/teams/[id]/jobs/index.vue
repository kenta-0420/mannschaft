<script setup lang="ts">
import type {
  JobPagedMeta,
  JobPostingStatus,
  JobPostingSummaryResponse,
} from '~/types/jobmatching'

/**
 * F13.1 チーム配下求人一覧（Requester 視点）。
 *
 * <p>ステータスフィルタ（ALL/DRAFT/OPEN/CLOSED/CANCELLED）とページングを提供する。
 * 一覧カードクリックで {@code /teams/{id}/jobs/{jobId}} に遷移。
 * 「求人投稿」ボタンで {@code /teams/{id}/jobs/new} に遷移。</p>
 */

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const api = useJobPostingApi()
const { error } = useNotification()

const teamId = computed(() => Number(route.params.id))

const PAGE_SIZE = 20

type StatusFilter = JobPostingStatus | 'ALL'
const statusFilter = ref<StatusFilter>('ALL')

const jobs = ref<JobPostingSummaryResponse[]>([])
const meta = ref<JobPagedMeta>({ total: 0, page: 0, size: PAGE_SIZE, totalPages: 0 })
const loading = ref(false)
const currentPage = ref(0)

const statusOptions = computed(() => [
  { label: t('jobmatching.filter.all'), value: 'ALL' as StatusFilter },
  { label: t('jobmatching.status.posting.DRAFT'), value: 'DRAFT' as StatusFilter },
  { label: t('jobmatching.status.posting.OPEN'), value: 'OPEN' as StatusFilter },
  { label: t('jobmatching.status.posting.CLOSED'), value: 'CLOSED' as StatusFilter },
  { label: t('jobmatching.status.posting.CANCELLED'), value: 'CANCELLED' as StatusFilter },
])

async function load(page = 0) {
  loading.value = true
  currentPage.value = page
  try {
    const res = await api.searchJobs({
      teamId: teamId.value,
      status: statusFilter.value === 'ALL' ? null : statusFilter.value,
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

function onFilterChange() {
  load(0)
}

function onPageChange(event: { page: number }) {
  load(event.page)
}

function goToDetail(jobId: number) {
  router.push(`/teams/${teamId.value}/jobs/${jobId}`)
}

function goToNew() {
  router.push(`/teams/${teamId.value}/jobs/new`)
}

onMounted(() => {
  if (!Number.isFinite(teamId.value)) return
  load(0)
})
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <div class="mb-4 flex items-center justify-between gap-3">
      <h1 class="text-2xl font-bold">
        {{ t('jobmatching.list.teamTitle') }}
      </h1>
      <Button
        :label="t('jobmatching.list.createButton')"
        icon="pi pi-plus"
        @click="goToNew"
      />
    </div>

    <!-- ステータスフィルタ -->
    <div class="mb-4">
      <SelectButton
        v-model="statusFilter"
        :options="statusOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        @change="onFilterChange"
      />
    </div>

    <!-- ローディング -->
    <div
      v-if="loading"
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
      <p>{{ t('jobmatching.list.empty') }}</p>
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
