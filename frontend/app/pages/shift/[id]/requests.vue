<script setup lang="ts">
import type {
  ShiftScheduleResponse,
  ShiftRequestResponse,
  ShiftRequestSummaryResponse,
  ShiftPreference,
} from '~/types/shift'
import { preferenceToI18nKey } from '~/utils/shiftPreference'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamStore = useTeamStore()
const { getSchedule } = useShiftApi()
const { listRequests, getRequestSummary } = useShiftRequestApi()
const { handleApiError } = useErrorHandler()

const scheduleId = computed(() => Number(route.params.id))

// =====================================================
// データ取得
// =====================================================
const schedule = ref<ShiftScheduleResponse | null>(null)
const requests = ref<ShiftRequestResponse[]>([])
const summary = ref<ShiftRequestSummaryResponse | null>(null)
const loading = ref(false)

onMounted(async () => {
  await teamStore.fetchMyTeams()
  loading.value = true
  try {
    const [s, reqs, sum] = await Promise.all([
      getSchedule(scheduleId.value),
      listRequests(scheduleId.value),
      getRequestSummary(scheduleId.value),
    ])
    schedule.value = s
    requests.value = reqs
    summary.value = sum
  } catch (error) {
    handleApiError(error)
  } finally {
    loading.value = false
  }
})

// =====================================================
// マトリクス構造
// =====================================================

/** ユーザー ID → 希望一覧のマップ */
const requestsByUser = computed<Map<number, ShiftRequestResponse[]>>(() => {
  const map = new Map<number, ShiftRequestResponse[]>()
  for (const req of requests.value) {
    const arr = map.get(req.userId) ?? []
    arr.push(req)
    map.set(req.userId, arr)
  }
  return map
})

/** 全ユーザー ID 一覧（重複なし） */
const userIds = computed<number[]>(() => [...new Set(requests.value.map((r) => r.userId))])

/** 全日付一覧（スケジュール期間内でリクエストが存在する日）*/
const dateList = computed<string[]>(() => {
  const dates = new Set(requests.value.map((r) => r.slotDate))
  return [...dates].sort()
})

/** userId × slotDate でプリファレンスを引く */
function getPreference(userId: number, date: string): ShiftPreference | null {
  const arr = requestsByUser.value.get(userId)
  if (!arr) return null
  const req = arr.find((r) => r.slotDate === date)
  return req?.preference ?? null
}

// =====================================================
// サマリー表示
// =====================================================
const preferenceKeys: ShiftPreference[] = [
  'PREFERRED',
  'AVAILABLE',
  'WEAK_REST',
  'STRONG_REST',
  'ABSOLUTE_REST',
]

function summaryCount(pref: ShiftPreference): number {
  if (!summary.value) return 0
  const map: Record<ShiftPreference, number> = {
    PREFERRED: summary.value.preferredCount,
    AVAILABLE: summary.value.availableCount,
    WEAK_REST: summary.value.weakRestCount,
    STRONG_REST: summary.value.strongRestCount,
    ABSOLUTE_REST: summary.value.absoluteRestCount,
  }
  return map[pref]
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr)
  const days = ['日', '月', '火', '水', '木', '金', '土']
  return `${d.getMonth() + 1}/${d.getDate()}(${days[d.getDay()] ?? ''})`
}

// 提出率
const submissionRate = computed(() => {
  if (!summary.value || summary.value.totalMembers === 0) return 0
  return Math.round((summary.value.submittedCount / summary.value.totalMembers) * 100)
})

// 未提出ユーザー（全メンバー − 提出者）の概算
const pendingCount = computed(() => summary.value?.pendingCount ?? 0)
</script>

<template>
  <div class="mx-auto max-w-7xl px-4 py-6">
    <!-- ナビゲーション -->
    <div class="mb-4 flex items-center gap-2">
      <BackButton :to="`/shift/${scheduleId}`" />
      <h1 class="text-xl font-bold text-surface-800 dark:text-surface-100">
        {{ schedule?.title ?? '...' }}
      </h1>
      <ShiftStatusBadge v-if="schedule" :status="schedule.status" />
    </div>

    <!-- タブ -->
    <nav class="mb-6 flex gap-1 overflow-x-auto border-b border-surface-200 dark:border-surface-700">
      <NuxtLink
        :to="`/shift/${scheduleId}`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-calendar" />{{ t('shift.detail.tabOverview') }}
      </NuxtLink>
      <NuxtLink
        :to="`/shift/${scheduleId}/edit`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-pencil" />{{ t('shift.detail.tabEdit') }}
      </NuxtLink>
      <span class="flex shrink-0 items-center gap-1.5 border-b-2 border-primary px-4 py-2 text-sm font-medium text-primary">
        <i class="pi pi-list" />{{ t('shift.detail.tabRequests') }}
      </span>
      <NuxtLink
        :to="`/shift/${scheduleId}/work-constraints`"
        class="flex shrink-0 items-center gap-1.5 px-4 py-2 text-sm font-medium text-surface-500 transition-colors hover:text-surface-800 dark:hover:text-surface-200"
      >
        <i class="pi pi-shield" />{{ t('shift.detail.tabConstraints') }}
      </NuxtLink>
    </nav>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- サマリーカード -->
      <div v-if="summary" class="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <!-- 提出率 -->
        <div class="col-span-2 rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900 sm:col-span-1">
          <p class="mb-1 text-xs text-surface-500">{{ t('shift.requests.submissionRate') }}</p>
          <p class="text-2xl font-bold text-primary">{{ submissionRate }}%</p>
          <p class="text-xs text-surface-400">
            {{ summary.submittedCount }} / {{ summary.totalMembers }}
          </p>
        </div>

        <!-- 未提出数 -->
        <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900">
          <p class="mb-1 text-xs text-surface-500">{{ t('shift.requests.pending') }}</p>
          <p class="text-2xl font-bold" :class="pendingCount > 0 ? 'text-orange-500' : 'text-surface-400'">
            {{ pendingCount }}
          </p>
        </div>

        <!-- 5段階カウンタ -->
        <div
          v-for="pref in preferenceKeys"
          :key="pref"
          class="flex items-center gap-2 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-900"
        >
          <ShiftPreferenceIcon :preference="pref" />
          <div>
            <p class="text-xs text-surface-500">{{ t(preferenceToI18nKey(pref)) }}</p>
            <p class="font-bold text-surface-800 dark:text-surface-100">{{ summaryCount(pref) }}</p>
          </div>
        </div>
      </div>

      <!-- マトリクス -->
      <section>
        <h2 class="mb-3 text-base font-semibold text-surface-700 dark:text-surface-300">
          {{ t('shift.requests.matrixTitle') }}
        </h2>

        <div v-if="userIds.length === 0 || dateList.length === 0">
          <DashboardEmptyState icon="pi pi-inbox" :message="t('shift.requests.noRequests')" />
        </div>

        <div v-else class="overflow-x-auto rounded-xl border border-surface-200 dark:border-surface-700">
          <table class="w-full border-collapse text-sm">
            <thead>
              <tr>
                <th class="sticky left-0 min-w-[80px] bg-surface-50 px-3 py-2 text-left text-xs font-medium text-surface-500 dark:bg-surface-800">
                  {{ t('shift.requests.colUser') }}
                </th>
                <th
                  v-for="date in dateList"
                  :key="date"
                  class="min-w-[60px] bg-surface-50 px-2 py-2 text-center text-xs font-medium text-surface-500 dark:bg-surface-800"
                >
                  {{ formatDate(date) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="userId in userIds"
                :key="userId"
                class="border-t border-surface-100 dark:border-surface-700"
              >
                <!-- ユーザー ID（実装注: 表示名は userStore 統合時に置換） -->
                <td class="sticky left-0 whitespace-nowrap bg-surface-0 px-3 py-2 font-medium dark:bg-surface-900">
                  <span class="text-xs text-surface-500">UID:{{ userId }}</span>
                </td>
                <!-- 各日付のプリファレンス -->
                <td
                  v-for="date in dateList"
                  :key="date"
                  class="px-2 py-2 text-center"
                >
                  <ShiftPreferenceIcon
                    v-if="getPreference(userId, date)"
                    :preference="getPreference(userId, date)!"
                    size="sm"
                  />
                  <span v-else class="text-xs text-surface-200 dark:text-surface-700">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 希望凡例 -->
      <section class="mt-6">
        <h3 class="mb-2 text-sm font-medium text-surface-500">{{ t('shift.requests.legend') }}</h3>
        <div class="flex flex-wrap gap-2">
          <div
            v-for="pref in preferenceKeys"
            :key="pref"
            class="flex items-center gap-1.5"
          >
            <ShiftPreferenceIcon :preference="pref" show-label size="sm" />
          </div>
        </div>
      </section>
    </template>
  </div>
</template>
