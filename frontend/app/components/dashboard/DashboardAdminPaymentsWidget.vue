<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — 支払サマリウィジェット（ADMIN_ORG_PAYMENTS）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.3③
 *
 * - 組織スコープ専用。`getAdminPaymentSummary(orgSlug)` を消費する。
 * - 表示: 未収 N 件 / 期限超過 N 件。
 * - 導線: `/organizations/[slug]/payments`（既存正本・P4 A-1方針。/admin/payments 整備後に切替予定）。
 * - API 取得失敗は握りつぶさず注記で表示（症状を隠さない）。
 */
import type { AdminPaymentSummary } from '~/types/admin-dashboard-widgets'

const props = defineProps<{
  /** 組織の slug（getAdminPaymentSummary のパス・ハブ導線に使用）。 */
  slug: string
}>()

const { getAdminPaymentSummary } = useScopeTabApi()

const summary = ref<AdminPaymentSummary | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/**
 * 導線先: 支払管理ページ（既存正本ルート）。
 * 設計方針（P4 A-1）: `/admin/payments` はまだ存在しないため、
 * ハブカードと同様に既存の正本ルート `/organizations/[slug]/payments` へ直結する。
 */
const paymentsRoute = computed(() => `/organizations/${props.slug}/payments`)

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    summary.value = await getAdminPaymentSummary(props.slug)
    loaded.value = true
  } catch (e) {
    console.error('[DashboardAdminPaymentsWidget] fetch failed', e)
    fetchFailed.value = true
  } finally {
    loading.value = false
  }
}

watch(
  () => props.slug,
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
    <SectionCard :title="$t('adminConsole.lens.widgets.payments')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.payments.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <NuxtLink
          :to="paymentsRoute"
          class="flex items-center justify-between text-left hover:text-primary"
          :data-testid="`admin-payments-link`"
        >
          <span class="flex items-center gap-2">
            <i class="pi pi-credit-card text-2xl text-primary" aria-hidden="true" />
            <span class="text-2xl font-bold" :data-testid="`admin-payments-unsettled`">
              {{ summary?.unsettledCount ?? 0 }}
            </span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>

        <ul class="flex flex-col gap-1 text-xs text-surface-600 dark:text-surface-400">
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.payments.unsettled') }}</span>
            <span class="font-medium" :data-testid="`admin-payments-unsettled-count`">
              {{ summary?.unsettledCount ?? 0 }}
            </span>
          </li>
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.payments.overdue') }}</span>
            <span
              class="font-medium"
              :class="(summary?.overdueCount ?? 0) > 0 ? 'text-red-600 dark:text-red-400' : ''"
              :data-testid="`admin-payments-overdue-count`"
            >
              {{ summary?.overdueCount ?? 0 }}
            </span>
          </li>
        </ul>
      </div>
    </SectionCard>
  </div>
</template>
