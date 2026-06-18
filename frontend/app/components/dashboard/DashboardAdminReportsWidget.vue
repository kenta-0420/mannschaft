<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — 通報統計ウィジェット（ADMIN_TEAM_REPORTS / ADMIN_ORG_REPORTS）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2⑥ §2.3⑥
 *
 * - team/org 両スコープ対応。`getAdminReportStats(scopeType, slug)` を消費する。
 * - 表示: 未対応 N 件 / 確認中 N 件。
 * - 導線: F10.1 母体モデレーション画面（スコープ別）。
 * - API 取得失敗は握りつぶさず注記で表示（症状を隠さない）。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'
import type { AdminReportStats } from '~/types/admin-dashboard-widgets'

const props = defineProps<{
  scopeType: ScopeTabType
  /** スコープの slug（getAdminReportStats のパス・ハブ導線に使用）。 */
  slug: string
}>()

const { getAdminReportStats } = useScopeTabApi()

const stats = ref<AdminReportStats | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/** 導線先: モデレーション画面（スコープ別）。 */
const reportsRoute = computed(() => {
  const base = props.scopeType === 'TEAM' ? 'teams' : 'organizations'
  return `/${base}/${props.slug}/admin/reports`
})

async function fetchStats() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    stats.value = await getAdminReportStats(props.scopeType, props.slug)
    loaded.value = true
  } catch (e) {
    console.error('[DashboardAdminReportsWidget] fetch failed', e)
    fetchFailed.value = true
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.scopeType, props.slug],
  () => {
    loaded.value = false
    stats.value = null
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
        fetchStats()
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
    <SectionCard :title="$t('adminConsole.lens.widgets.reports')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.reports.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <NuxtLink
          :to="reportsRoute"
          class="flex items-center justify-between text-left hover:text-primary"
          :data-testid="`admin-reports-link-${scopeType}`"
        >
          <span class="flex items-center gap-2">
            <i class="pi pi-flag text-2xl text-primary" aria-hidden="true" />
            <span
              class="text-2xl font-bold"
              :data-testid="`admin-reports-total-${scopeType}`"
            >
              {{ (stats?.pendingCount ?? 0) + (stats?.reviewingCount ?? 0) }}
            </span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>

        <ul class="flex flex-col gap-1 text-xs text-surface-600 dark:text-surface-400">
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.reports.pending') }}</span>
            <span
              class="font-medium"
              :class="(stats?.pendingCount ?? 0) > 0 ? 'text-amber-600 dark:text-amber-400' : ''"
              :data-testid="`admin-reports-pending-${scopeType}`"
            >
              {{ stats?.pendingCount ?? 0 }}
            </span>
          </li>
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.reports.reviewing') }}</span>
            <span class="font-medium" :data-testid="`admin-reports-reviewing-${scopeType}`">
              {{ stats?.reviewingCount ?? 0 }}
            </span>
          </li>
        </ul>
      </div>
    </SectionCard>
  </div>
</template>
