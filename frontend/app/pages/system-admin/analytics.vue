<script setup lang="ts">
import {
  Chart,
  LineController,
  BarController,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  BarElement,
  Title,
  Tooltip,
  Legend,
  Filler,
} from 'chart.js'

Chart.register(
  LineController,
  BarController,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  BarElement,
  Title,
  Tooltip,
  Legend,
  Filler,
)

definePageMeta({ middleware: 'auth' })

const analyticsApi = useSystemAdminAnalyticsApi()
const notification = useNotification()

// ---- 期間選択 ----
const periodOptions = [
  { label: '今月', value: 'this_month' },
  { label: '先月', value: 'last_month' },
  { label: '過去3ヶ月', value: 'last_3_months' },
  { label: '今年', value: 'this_year' },
]
const selectedPeriod = ref<string>('this_month')

function getPeriodRange(period: string): { from: string; to: string } {
  const now = new Date()
  const fmt = (d: Date) => d.toISOString().slice(0, 10)

  if (period === 'this_month') {
    const from = new Date(now.getFullYear(), now.getMonth(), 1)
    const to = new Date(now.getFullYear(), now.getMonth() + 1, 0)
    return { from: fmt(from), to: fmt(to) }
  }
  if (period === 'last_month') {
    const from = new Date(now.getFullYear(), now.getMonth() - 1, 1)
    const to = new Date(now.getFullYear(), now.getMonth(), 0)
    return { from: fmt(from), to: fmt(to) }
  }
  if (period === 'last_3_months') {
    const from = new Date(now.getFullYear(), now.getMonth() - 3, 1)
    return { from: fmt(from), to: fmt(now) }
  }
  // this_year
  const from = new Date(now.getFullYear(), 0, 1)
  return { from: fmt(from), to: fmt(now) }
}

// ---- データ ----
type AnyRecord = Record<string, unknown>

const loading = ref(true)
const revenueSummary = ref<AnyRecord | null>(null)
const revenueTrend = ref<AnyRecord | null>(null)
const usersTrend = ref<AnyRecord | null>(null)
const churnAnalysis = ref<AnyRecord | null>(null)
const moduleRanking = ref<AnyRecord | null>(null)

async function load() {
  loading.value = true
  try {
    const range = getPeriodRange(selectedPeriod.value)
    const [summary, trend, users, churn, modules] = await Promise.all([
      analyticsApi.getRevenueSummary().catch(() => null),
      analyticsApi.getRevenueTrend(range).catch(() => null),
      analyticsApi.getUsersTrend(range).catch(() => null),
      analyticsApi.getChurnAnalysis().catch(() => null),
      analyticsApi.getModuleRanking().catch(() => null),
    ])
    revenueSummary.value = (summary as { data: AnyRecord } | null)?.data ?? null
    revenueTrend.value = (trend as { data: AnyRecord } | null)?.data ?? null
    usersTrend.value = (users as { data: AnyRecord } | null)?.data ?? null
    churnAnalysis.value = (churn as { data: AnyRecord } | null)?.data ?? null
    moduleRanking.value = (modules as { data: AnyRecord } | null)?.data ?? null
  } catch {
    notification.error('分析データの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

watch(selectedPeriod, load)
onMounted(load)

// ---- KPI計算 ----
const mrr = computed(() => {
  const v = revenueSummary.value?.mrr
  return typeof v === 'number' ? v.toLocaleString() : '—'
})

const mrrGrowthRate = computed(() => {
  const v = revenueSummary.value?.mrrGrowthRate
  if (typeof v !== 'number') return null
  return v
})

const totalUsers = computed(() => {
  const v = revenueSummary.value?.totalActiveUsers
  return typeof v === 'number' ? v.toLocaleString() : '—'
})

const churnRate = computed(() => {
  const points = (churnAnalysis.value?.points as AnyRecord[] | undefined) ?? []
  if (points.length === 0) return '—'
  const latest = points[points.length - 1]
  const v = latest?.userChurnRate
  return typeof v === 'number' ? (v * 100).toFixed(1) + '%' : '—'
})

const payingUsers = computed(() => {
  const v = revenueSummary.value?.payingUsers
  return typeof v === 'number' ? v.toLocaleString() : '—'
})

// ---- 収益トレンド折れ線グラフ ----
const revenueChartRef = ref<HTMLCanvasElement | null>(null)
let revenueChartInstance: Chart | null = null

watch(revenueTrend, (data) => {
  if (!revenueChartRef.value) return
  if (revenueChartInstance) revenueChartInstance.destroy()

  const points = (data?.points as AnyRecord[] | undefined) ?? []
  const labels = points.map((p) => String(p.period ?? ''))
  const values = points.map((p) => Number(p.netRevenue ?? 0))

  revenueChartInstance = new Chart(revenueChartRef.value, {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: '純収益（円）',
          data: values,
          borderColor: '#6366f1',
          backgroundColor: 'rgba(99, 102, 241, 0.1)',
          fill: true,
          tension: 0.3,
          pointRadius: 4,
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { position: 'top' },
        title: { display: false },
      },
      scales: { y: { beginAtZero: true } },
    },
  })
})

// ---- ユーザー増減棒グラフ ----
const usersChartRef = ref<HTMLCanvasElement | null>(null)
let usersChartInstance: Chart | null = null

watch(usersTrend, (data) => {
  if (!usersChartRef.value) return
  if (usersChartInstance) usersChartInstance.destroy()

  const points = (data?.points as AnyRecord[] | undefined) ?? []
  const labels = points.map((p) => String(p.period ?? ''))
  const newUsers = points.map((p) => Number(p.newUsers ?? 0))
  const churnedUsers = points.map((p) => Number(p.churnedUsers ?? 0))

  usersChartInstance = new Chart(usersChartRef.value, {
    type: 'bar',
    data: {
      labels,
      datasets: [
        {
          label: '新規ユーザー',
          data: newUsers,
          backgroundColor: '#22c55e',
        },
        {
          label: '離脱ユーザー',
          data: churnedUsers.map((v) => -v),
          backgroundColor: '#ef4444',
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { position: 'top' } },
      scales: { y: { beginAtZero: false } },
    },
  })
})

// ---- モジュールランキング ----
const moduleRows = computed(() => {
  const modules = (moduleRanking.value?.modules as AnyRecord[] | undefined) ?? []
  return modules.slice(0, 10)
})

onUnmounted(() => {
  revenueChartInstance?.destroy()
  usersChartInstance?.destroy()
})
</script>

<template>
  <div class="mx-auto max-w-screen-xl">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center justify-between">
      <div>
        <div class="mb-1 flex items-center gap-2">
          <span
            class="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
          >
            SYSTEM ADMIN
          </span>
        </div>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">ビジネス分析</h1>
        <p class="mt-0.5 text-sm text-surface-500">収益・ユーザー・モジュールの統計を確認します</p>
      </div>
      <div class="flex items-center gap-3">
        <Select
          v-model="selectedPeriod"
          :options="periodOptions"
          option-label="label"
          option-value="value"
          class="w-40"
        />
        <Button
          v-tooltip.left="'再読み込み'"
          icon="pi pi-refresh"
          text
          rounded
          :loading="loading"
          @click="load"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- KPIカード -->
      <div class="mb-6 grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-4">
        <Card>
          <template #content>
            <div class="flex items-start justify-between">
              <div>
                <p class="text-sm text-surface-500">月間収益 (MRR)</p>
                <p class="mt-1 text-3xl font-bold text-indigo-600 dark:text-indigo-400">
                  ¥{{ mrr }}
                </p>
              </div>
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-indigo-50 dark:bg-indigo-900/20"
              >
                <i class="pi pi-dollar text-lg text-indigo-500" />
              </div>
            </div>
            <div
              v-if="mrrGrowthRate !== null"
              class="mt-2 text-xs"
              :class="mrrGrowthRate >= 0 ? 'text-green-600' : 'text-red-500'"
            >
              <i :class="mrrGrowthRate >= 0 ? 'pi pi-arrow-up' : 'pi pi-arrow-down'" />
              前月比 {{ Math.abs(mrrGrowthRate * 100).toFixed(1) }}%
            </div>
          </template>
        </Card>

        <Card>
          <template #content>
            <div class="flex items-start justify-between">
              <div>
                <p class="text-sm text-surface-500">アクティブユーザー</p>
                <p class="mt-1 text-3xl font-bold text-green-600 dark:text-green-400">
                  {{ totalUsers }}
                </p>
              </div>
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-green-50 dark:bg-green-900/20"
              >
                <i class="pi pi-users text-lg text-green-500" />
              </div>
            </div>
            <div class="mt-2 text-xs text-surface-400">有料ユーザー: {{ payingUsers }}名</div>
          </template>
        </Card>

        <Card>
          <template #content>
            <div class="flex items-start justify-between">
              <div>
                <p class="text-sm text-surface-500">解約率（直近）</p>
                <p class="mt-1 text-3xl font-bold text-red-600 dark:text-red-400">
                  {{ churnRate }}
                </p>
              </div>
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-red-50 dark:bg-red-900/20"
              >
                <i class="pi pi-user-minus text-lg text-red-500" />
              </div>
            </div>
            <div class="mt-2 text-xs text-surface-400">ユーザーチャーンレート</div>
          </template>
        </Card>

        <Card>
          <template #content>
            <div class="flex items-start justify-between">
              <div>
                <p class="text-sm text-surface-500">有料転換ユーザー数</p>
                <p class="mt-1 text-3xl font-bold text-amber-600 dark:text-amber-400">
                  {{ payingUsers }}
                </p>
              </div>
              <div
                class="flex h-10 w-10 items-center justify-center rounded-lg bg-amber-50 dark:bg-amber-900/20"
              >
                <i class="pi pi-credit-card text-lg text-amber-500" />
              </div>
            </div>
            <div class="mt-2 text-xs text-surface-400">
              転換率:
              {{
                revenueSummary?.payingRatio !== undefined
                  ? (Number(revenueSummary.payingRatio) * 100).toFixed(1) + '%'
                  : '—'
              }}
            </div>
          </template>
        </Card>
      </div>

      <!-- グラフエリア -->
      <div class="mb-6 grid grid-cols-1 gap-6 xl:grid-cols-2">
        <!-- 収益トレンド -->
        <div
          class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
        >
          <h2 class="mb-4 text-base font-semibold text-surface-700 dark:text-surface-200">
            収益トレンド
          </h2>
          <div class="relative h-64">
            <canvas ref="revenueChartRef" />
            <div
              v-if="!revenueTrend || ((revenueTrend.points as unknown[])?.length ?? 0) === 0"
              class="absolute inset-0 flex items-center justify-center text-sm text-surface-400"
            >
              データがありません
            </div>
          </div>
        </div>

        <!-- ユーザー増減 -->
        <div
          class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
        >
          <h2 class="mb-4 text-base font-semibold text-surface-700 dark:text-surface-200">
            ユーザー増減
          </h2>
          <div class="relative h-64">
            <canvas ref="usersChartRef" />
            <div
              v-if="!usersTrend || ((usersTrend.points as unknown[])?.length ?? 0) === 0"
              class="absolute inset-0 flex items-center justify-center text-sm text-surface-400"
            >
              データがありません
            </div>
          </div>
        </div>
      </div>

      <!-- モジュール別収益ランキング -->
      <div
        class="rounded-xl border border-surface-300 bg-surface-0 p-6 dark:border-surface-600 dark:bg-surface-800"
      >
        <h2 class="mb-4 text-base font-semibold text-surface-700 dark:text-surface-200">
          モジュール別収益ランキング
        </h2>
        <DataTable
          :value="moduleRows"
          striped-rows
          class="text-sm"
          :rows="10"
        >
          <template #empty>
            <div class="py-6 text-center text-surface-400">データがありません</div>
          </template>
          <Column header="#" style="width: 3rem">
            <template #body="{ index }">
              <span class="font-semibold text-surface-500">{{ index + 1 }}</span>
            </template>
          </Column>
          <Column field="moduleName" header="モジュール名" />
          <Column field="activeTeams" header="利用チーム数">
            <template #body="{ data: row }">
              {{ Number(row.activeTeams ?? 0).toLocaleString() }}
            </template>
          </Column>
          <Column field="revenue" header="収益（円）">
            <template #body="{ data: row }">
              ¥{{ Number(row.revenue ?? 0).toLocaleString() }}
            </template>
          </Column>
          <Column field="revenueSharePct" header="収益シェア">
            <template #body="{ data: row }">
              {{ row.revenueSharePct !== undefined ? (Number(row.revenueSharePct) * 100).toFixed(1) + '%' : '—' }}
            </template>
          </Column>
          <Column field="growthRate" header="成長率">
            <template #body="{ data: row }">
              <span
                :class="Number(row.growthRate ?? 0) >= 0 ? 'text-green-600' : 'text-red-500'"
              >
                {{ row.growthRate !== undefined ? (Number(row.growthRate) * 100).toFixed(1) + '%' : '—' }}
              </span>
            </template>
          </Column>
          <Column field="churnRate" header="解約率">
            <template #body="{ data: row }">
              {{ row.churnRate !== undefined ? (Number(row.churnRate) * 100).toFixed(1) + '%' : '—' }}
            </template>
          </Column>
        </DataTable>
      </div>
    </template>
  </div>
</template>
