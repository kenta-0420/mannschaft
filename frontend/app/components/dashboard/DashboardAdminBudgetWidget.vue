<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — 予算ウィジェット（ADMIN_TEAM_BUDGET / ADMIN_ORG_BUDGET）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md
 *
 * - team/org 両スコープ対応。`getAdminBudgetSummary(scopeType, slug)` を消費する。
 * - 表示: 現年度の 配分（導線）/ 実績 / 残 / 超過カテゴリ数。
 * - 現年度未設定（hasCurrentFiscalYear=false）時は数値でなく「当年度未設定」＋導線のみ表示。
 * - 導線: /teams|organizations/[slug]/budget（既存の予算ページ）。
 * - API 取得失敗は握りつぶさず注記で表示（症状を隠さない）。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'
import type { AdminBudgetSummary } from '~/types/admin-dashboard-widgets'

const props = defineProps<{
  scopeType: ScopeTabType
  /** スコープの slug（getAdminBudgetSummary のパス・導線に使用）。 */
  slug: string
}>()

const { getAdminBudgetSummary } = useScopeTabApi()

const summary = ref<AdminBudgetSummary | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/** 導線先: 予算ページ（team / org の既存ルート）。 */
const budgetRoute = computed(() => {
  const base = props.scopeType === 'TEAM' ? 'teams' : 'organizations'
  return `/${base}/${props.slug}/budget`
})

/** 超過カテゴリがあるか（強調表示判定）。 */
const hasOverBudget = computed(() => (summary.value?.overBudgetCategoryCount ?? 0) > 0)

/** 金額表示（既存 budget ページと同じ ¥ + ロケール桁区切り）。 */
function yen(amount: number | undefined): string {
  return `¥${(amount ?? 0).toLocaleString()}`
}

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    summary.value = await getAdminBudgetSummary(props.scopeType, props.slug)
    loaded.value = true
  } catch (e) {
    console.error('[DashboardAdminBudgetWidget] fetch failed', e)
    fetchFailed.value = true
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.scopeType, props.slug],
  () => {
    loaded.value = false
    summary.value = null
    fetchFailed.value = false
    if (rootEl.value) observeViewport()
  },
)

function observeViewport() {
  if (!import.meta.client || !rootEl.value) return
  observer?.disconnect()
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        fetchSummary()
        observer?.disconnect()
      }
    }
  })
  observer.observe(rootEl.value)
}

onMounted(() => {
  observeViewport()
})

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<template>
  <div ref="rootEl">
    <SectionCard :title="$t('adminConsole.lens.widgets.budget')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.budget.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <!-- 現年度未設定: 数値でなく注記＋導線のみ -->
        <template v-if="!summary?.hasCurrentFiscalYear">
          <NuxtLink
            :to="budgetRoute"
            class="flex items-center justify-between text-left hover:text-primary"
            :data-testid="`admin-budget-link-${scopeType}`"
          >
            <span class="flex items-center gap-2">
              <i class="pi pi-wallet text-2xl text-surface-400" aria-hidden="true" />
              <span class="text-sm text-surface-600 dark:text-surface-400" data-testid="admin-budget-no-fiscal-year">
                {{ $t('adminConsole.lens.budget.noFiscalYear') }}
              </span>
            </span>
            <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
          </NuxtLink>
        </template>

        <!-- 現年度あり: 配分/実績/残/超過カテゴリ数 -->
        <template v-else>
          <NuxtLink
            :to="budgetRoute"
            class="flex items-center justify-between text-left hover:text-primary"
            :data-testid="`admin-budget-link-${scopeType}`"
          >
            <span class="flex items-center gap-2">
              <i class="pi pi-wallet text-2xl text-primary" aria-hidden="true" />
              <span class="text-2xl font-bold" :data-testid="`admin-budget-allocation-${scopeType}`">
                {{ yen(summary?.allocation) }}
              </span>
            </span>
            <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
          </NuxtLink>

          <ul class="flex flex-col gap-1 text-xs text-surface-600 dark:text-surface-400">
            <li class="flex items-center justify-between">
              <span>{{ $t('adminConsole.lens.budget.allocation') }}</span>
              <span class="font-medium" :data-testid="`admin-budget-allocation-amount-${scopeType}`">
                {{ yen(summary?.allocation) }}
              </span>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ $t('adminConsole.lens.budget.actual') }}</span>
              <span class="font-medium" :data-testid="`admin-budget-actual-${scopeType}`">
                {{ yen(summary?.actual) }}
              </span>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ $t('adminConsole.lens.budget.remaining') }}</span>
              <span
                class="font-medium"
                :class="(summary?.remaining ?? 0) < 0 ? 'text-red-600 dark:text-red-400' : ''"
                :data-testid="`admin-budget-remaining-${scopeType}`"
              >
                {{ yen(summary?.remaining) }}
              </span>
            </li>
            <li class="flex items-center justify-between">
              <span>{{ $t('adminConsole.lens.budget.overBudgetCount') }}</span>
              <span
                class="font-medium"
                :class="hasOverBudget ? 'text-red-600 dark:text-red-400' : ''"
                :data-testid="`admin-budget-over-count-${scopeType}`"
              >
                {{ summary?.overBudgetCategoryCount ?? 0 }}
              </span>
            </li>
          </ul>
        </template>
      </div>
    </SectionCard>
  </div>
</template>
