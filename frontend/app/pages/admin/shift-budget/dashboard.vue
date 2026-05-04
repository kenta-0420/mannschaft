<script setup lang="ts">
import type {
  AllocationResponse,
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
const scopeStore = useScopeStore()
const notification = useNotification()
const api = useShiftBudgetApi()

const organizationId = computed(() => {
  if (scopeStore.current.type !== 'organization') return null
  return scopeStore.current.id
})

const allocations = ref<AllocationResponse[]>([])
const loading = ref(false)

const monthlyCloseVisible = ref(false)
const monthlyCloseForm = ref<{ yearMonth: string }>({ yearMonth: '' })
const monthlyCloseExecuting = ref(false)

function defaultPreviousMonth(): string {
  const now = new Date()
  const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const yyyy = prev.getFullYear()
  const mm = String(prev.getMonth() + 1).padStart(2, '0')
  return `${yyyy}-${mm}`
}

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

function openMonthlyClose() {
  monthlyCloseForm.value = { yearMonth: defaultPreviousMonth() }
  monthlyCloseVisible.value = true
}

async function executeMonthlyClose() {
  if (!organizationId.value) return
  const yearMonth = monthlyCloseForm.value.yearMonth
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(yearMonth)) {
    notification.error(t('shiftBudget.monthlyClose.error'))
    return
  }
  monthlyCloseExecuting.value = true
  try {
    const request: MonthlyCloseRequest = {
      organization_id: organizationId.value,
      year_month: yearMonth,
    }
    const res = await api.executeMonthlyClose(request)
    if (res.already_closed_allocations > 0 && res.closed_allocations === 0) {
      notification.info(t('shiftBudget.monthlyClose.alreadyClosed'))
    }
    else {
      notification.success(
        t('shiftBudget.monthlyClose.success', {
          month: yearMonth,
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

watch(organizationId, () => load())
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <div class="mb-4 flex items-center justify-between">
      <PageHeader :title="t('shiftBudget.dashboard.title')" />
      <Button
        v-if="organizationId"
        :label="t('shiftBudget.monthlyClose.button')"
        icon="pi pi-calendar-times"
        severity="secondary"
        @click="openMonthlyClose"
      />
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
                <span>{{ data.consumed_amount?.toLocaleString() ?? '0' }}</span>
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
          <label class="mb-1 block text-sm font-medium">{{ t('shiftBudget.monthlyClose.yearMonth') }}</label>
          <InputText v-model="monthlyCloseForm.yearMonth" placeholder="YYYY-MM" class="w-full" />
          <p class="mt-1 text-xs text-surface-500">{{ t('shiftBudget.monthlyClose.yearMonthHint') }}</p>
        </div>
        <Message severity="info" :closable="false">
          {{ t('shiftBudget.monthlyClose.confirm', { month: monthlyCloseForm.yearMonth }) }}
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
            @click="executeMonthlyClose"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
