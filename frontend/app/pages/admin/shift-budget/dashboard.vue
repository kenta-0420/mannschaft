<script setup lang="ts">
import { useShiftBudgetAdminAccess } from '~/composables/shift/useShiftBudgetAdminAccess'
import dayjs from 'dayjs'
import type {
  AllocationResponse,
  ClosePeriodResponse,
  ConsumptionStatus,
  MonthlyCloseRequest,
} from '~/types/shiftBudget'

/**
 * F08.7 Phase 10-γ: 予算ダッシュボード (`/admin/shift-budget/dashboard`)。
 *
 * <p>設計書 §7.4 に準拠。allocation 一覧から消化率の集計を表示。
 * 月次締め実行ボタン (BUDGET_ADMIN) と関連画面リンクを提供する。</p>
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { userTimezone } = useDatetime()
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

/**
 * 予算の管理操作（BUDGET_ADMIN）を出してよいかの判定。
 * BE は権限が無ければ 403 を返す（その防御は外していない）。ここでは弾かれる操作を
 * そもそも画面に出さないために使う。CMP-260913-1251。
 */
const { canManageBudget, ensureLoaded: ensureBudgetAdminAccess } = useShiftBudgetAdminAccess()

const allocations = ref<AllocationResponse[]>([])
const loading = ref(false)

const monthlyCloseVisible = ref(false)
const monthlyCloseForm = ref<{ periodKey: string | null }>({ periodKey: null })
const monthlyCloseExecuting = ref(false)
const closePeriods = ref<ClosePeriodResponse[]>([])

/** 期間一覧の取得範囲（期間キー。今日を含む期間の 12 か月前〜翌月。BE の上限 36 か月以内） */
const CLOSE_PERIOD_LOOKBACK_MONTHS = 12

/**
 * 終了済み（終了日当日を含む）の期間だけが締められる。判定はサーバーの業務日（Asia/Tokyo）で BE が返す ended を正とし、
 * 利用者端末のタイムゾーンの「今日」では判定しない（締め API の 409 判定とずらさない。CMP-260909-2152 再検分 P2-4）。
 */
function isEnded(period: ClosePeriodResponse): boolean {
  return period.ended
}

function periodLabel(period: ClosePeriodResponse): string {
  return t('shiftBudget.closePeriod.label', { start: period.period_start, end: period.period_end })
}

const closePeriodOptions = computed(() =>
  closePeriods.value.map(p => ({
    value: p.period_key,
    label: isEnded(p) ? periodLabel(p) : `${periodLabel(p)}（${t('shiftBudget.closePeriod.notEnded')}）`,
    disabled: !isEnded(p),
  })),
)

const selectedClosePeriod = computed(() =>
  closePeriods.value.find(p => p.period_key === monthlyCloseForm.value.periodKey) ?? null,
)

/** 選択中の期間が終了済みのときだけ実行できる（未終了は 409 を待たずに止める） */
const canExecuteMonthlyClose = computed(() =>
  selectedClosePeriod.value !== null && isEnded(selectedClosePeriod.value),
)

async function load() {
  if (!organizationId.value) {
    allocations.value = []
    return
  }
  loading.value = true
  try {
    // ページサイズ大きめで一括取得（ダッシュボード用途）
    const res = await api.listAllocations(organizationId.value, 0, 100)
    allocations.value = res.items
  }
  catch {
    notification.error(t('shiftBudget.dashboard.loadError'))
  }
  finally {
    loading.value = false
  }
}

function consumptionRate(a: AllocationResponse): number {
  const allocated = a.allocated_amount ?? 0
  const consumed = a.consumed_amount ?? 0
  if (allocated === 0) return 0
  return consumed / allocated
}

function statusOf(a: AllocationResponse): ConsumptionStatus {
  const r = consumptionRate(a)
  if (r >= 1.2) return 'SEVERE_EXCEEDED'
  if (r >= 1.0) return 'EXCEEDED'
  if (r >= 0.8) return 'WARN'
  return 'OK'
}

const summary = computed(() => {
  const counts: Record<ConsumptionStatus, number> = {
    OK: 0,
    WARN: 0,
    EXCEEDED: 0,
    SEVERE_EXCEEDED: 0,
  }
  for (const a of allocations.value) {
    counts[statusOf(a)] += 1
  }
  return counts
})

async function openMonthlyClose() {
  if (!organizationId.value) return
  monthlyCloseForm.value = { periodKey: null }
  closePeriods.value = []
  monthlyCloseVisible.value = true
  const today = dayjs().tz(userTimezone.value)
  try {
    const res = await api.listClosePeriods(
      organizationId.value,
      today.subtract(CLOSE_PERIOD_LOOKBACK_MONTHS, 'month').format('YYYY-MM'),
      today.add(1, 'month').format('YYYY-MM'),
    )
    closePeriods.value = [...res.items].sort((a, b) => a.period_key.localeCompare(b.period_key))
    // 既定は終了済みの最新期間
    const latestEnded = [...closePeriods.value].reverse().find(isEnded)
    monthlyCloseForm.value = { periodKey: latestEnded?.period_key ?? null }
  }
  catch {
    notification.error(t('shiftBudget.monthlyClose.periodLoadError'))
  }
}

async function executeMonthlyClose() {
  if (!organizationId.value) return
  const period = selectedClosePeriod.value
  if (!period || !isEnded(period)) return
  const yearMonth = period.period_key
  monthlyCloseExecuting.value = true
  try {
    const request: MonthlyCloseRequest = {
      organization_id: Number(organizationId.value),
      year_month: yearMonth,
    }
    const res = await api.executeMonthlyClose(request)
    // 段取り役は Post 失敗・ABORTED を HTTP 200 の failed_allocations に集計する（CMP-260909-2152 Codex 検分3 P2-1）。
    // 失敗が1件でもあれば未締めの割当が残るので、成功・締め済みとは区別して通知する。
    if (res.failed_allocations > 0 && res.closed_allocations === 0) {
      // 全件失敗: 何も締まっていない。ダイアログを閉じずに再実行できるようにする
      notification.error(t('shiftBudget.monthlyClose.allFailed', {
        period: periodLabel(period),
        failed: res.failed_allocations,
      }))
      return
    }
    if (res.failed_allocations > 0) {
      notification.warn(t('shiftBudget.monthlyClose.partiallyFailed', {
        period: periodLabel(period),
        closed: res.closed_allocations,
        failed: res.failed_allocations,
      }))
    }
    else if (res.already_closed_allocations > 0 && res.closed_allocations === 0) {
      notification.info(t('shiftBudget.monthlyClose.alreadyClosed'))
    }
    else {
      notification.success(
        t('shiftBudget.monthlyClose.success', {
          period: periodLabel(period),
          count: res.closed_consumptions,
        }),
      )
    }
    monthlyCloseVisible.value = false
    await load()
  }
  catch {
    notification.error(t('shiftBudget.monthlyClose.error'))
  }
  finally {
    monthlyCloseExecuting.value = false
  }
}

watch(organizationId, () => {
  load()
  ensureBudgetAdminAccess()
})
onMounted(() => {
  load()
  ensureBudgetAdminAccess()
})
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('shiftBudget.dashboard.title')" />
      <div v-if="organizationId && canManageBudget" class="flex items-center gap-2">
        <NuxtLink to="/admin/shift-budget/settings" data-testid="closing-settings-link">
          <Button
            :label="t('shiftBudget.dashboard.viewClosingSettings')"
            icon="pi pi-cog"
            severity="secondary"
            text
            as="span"
          />
        </NuxtLink>
        <Button
          :label="t('shiftBudget.monthlyClose.button')"
          icon="pi pi-calendar-times"
          severity="secondary"
          @click="openMonthlyClose"
        />
      </div>
    </div>
    <p class="mb-6 text-sm text-surface-500">{{ t('shiftBudget.dashboard.subtitle') }}</p>

    <Message v-if="!organizationId" severity="warn" :closable="false" class="mb-4">
      {{ t('shiftBudget.scope.selectOrganization') }}
    </Message>

    <PageLoading v-else-if="loading" />

    <template v-else>
      <!-- KPI 4 カラムグリッド (PC), 2 カラム (タブレット), 1 カラム (モバイル) -->
      <div class="mb-6 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.ok') }}</p>
                <p class="text-3xl font-bold text-green-500">{{ summary.OK }}</p>
              </div>
              <i class="pi pi-check-circle text-3xl text-green-500" />
            </div>
          </template>
        </Card>
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.warn') }}</p>
                <p class="text-3xl font-bold text-yellow-500">{{ summary.WARN }}</p>
              </div>
              <i class="pi pi-exclamation-triangle text-3xl text-yellow-500" />
            </div>
          </template>
        </Card>
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.exceeded') }}</p>
                <p class="text-3xl font-bold text-orange-500">{{ summary.EXCEEDED }}</p>
              </div>
              <i class="pi pi-times-circle text-3xl text-orange-500" />
            </div>
          </template>
        </Card>
        <Card>
          <template #content>
            <div class="flex items-center justify-between">
              <div>
                <p class="text-sm text-surface-500">{{ t('shiftBudget.dashboard.kpi.severeExceeded') }}</p>
                <p class="text-3xl font-bold text-red-600">{{ summary.SEVERE_EXCEEDED }}</p>
              </div>
              <i class="pi pi-ban text-3xl text-red-600" />
            </div>
          </template>
        </Card>
      </div>

      <!-- 全 allocation 一覧 (簡易) -->
      <Card class="mb-6">
        <template #title>
          <span class="text-lg">{{ t('shiftBudget.allocation.list') }}</span>
        </template>
        <template #content>
          <DataTable :value="allocations" striped-rows data-key="id" :rows="10" paginator>
            <template #empty>
              <div class="py-4 text-center text-surface-500">{{ t('shiftBudget.allocation.empty') }}</div>
            </template>
            <Column field="id" :header="t('shiftBudget.allocation.id')" style="width: 80px" />
            <Column :header="t('shiftBudget.allocation.period')" style="width: 220px">
              <template #body="{ data }: { data: AllocationResponse }">
                <span class="text-sm">{{ data.period_start }} 〜 {{ data.period_end }}</span>
              </template>
            </Column>
            <Column :header="t('shiftBudget.allocation.amount')">
              <template #body="{ data }: { data: AllocationResponse }">
                <span>{{ data.allocated_amount?.toLocaleString() ?? '-' }} {{ data.currency }}</span>
              </template>
            </Column>
            <Column :header="t('shiftBudget.allocation.consumed')">
              <template #body="{ data }: { data: AllocationResponse }">
                <span>{{ data.consumed_amount?.toLocaleString() ?? '0' }} {{ data.currency }}</span>
              </template>
            </Column>
            <Column :header="t('shiftBudget.allocation.rate')" style="width: 200px">
              <template #body="{ data }: { data: AllocationResponse }">
                <ConsumptionRateBadge :rate="consumptionRate(data)" />
              </template>
            </Column>
          </DataTable>
        </template>
      </Card>

      <!-- 関連画面リンク -->
      <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
        <NuxtLink to="/admin/shift-budget/allocations" class="block">
          <Card class="cursor-pointer transition-all hover:shadow-lg">
            <template #content>
              <div class="flex items-center gap-3">
                <i class="pi pi-list text-2xl text-blue-500" />
                <span class="font-medium">{{ t('shiftBudget.dashboard.viewAllocations') }}</span>
              </div>
            </template>
          </Card>
        </NuxtLink>
        <NuxtLink to="/admin/shift-budget/alerts" class="block">
          <Card class="cursor-pointer transition-all hover:shadow-lg">
            <template #content>
              <div class="flex items-center gap-3">
                <i class="pi pi-bell text-2xl text-yellow-500" />
                <span class="font-medium">{{ t('shiftBudget.dashboard.viewAlerts') }}</span>
              </div>
            </template>
          </Card>
        </NuxtLink>
        <NuxtLink to="/admin/shift-budget/failed-events" class="block">
          <Card class="cursor-pointer transition-all hover:shadow-lg">
            <template #content>
              <div class="flex items-center gap-3">
                <i class="pi pi-exclamation-circle text-2xl text-red-500" />
                <span class="font-medium">{{ t('shiftBudget.dashboard.viewFailedEvents') }}</span>
              </div>
            </template>
          </Card>
        </NuxtLink>
      </div>
    </template>

    <!-- 月次締め実行モーダル -->
    <Dialog
      v-model:visible="monthlyCloseVisible"
      :header="t('shiftBudget.monthlyClose.title')"
      :style="{ width: '500px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.monthlyClose.period') }}</label>
          <Select
            v-model="monthlyCloseForm.periodKey"
            data-testid="monthly-close-period-select"
            :options="closePeriodOptions"
            option-label="label"
            option-value="value"
            option-disabled="disabled"
            :placeholder="t('shiftBudget.closePeriod.select')"
            class="w-full"
          />
          <p class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.monthlyClose.periodHint') }}</p>
        </div>
        <Message v-if="selectedClosePeriod" severity="info" :closable="false" data-testid="monthly-close-confirm">
          {{ t('shiftBudget.monthlyClose.confirm', { period: periodLabel(selectedClosePeriod) }) }}
        </Message>
        <Message v-else severity="warn" :closable="false">
          {{ t('shiftBudget.closePeriod.empty') }}
        </Message>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('shiftBudget.allocation.form.cancel')"
            severity="secondary"
            :disabled="monthlyCloseExecuting"
            @click="monthlyCloseVisible = false"
          />
          <Button
            :label="t('shiftBudget.monthlyClose.execute')"
            :loading="monthlyCloseExecuting"
            :disabled="!canExecuteMonthlyClose"
            @click="executeMonthlyClose"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
