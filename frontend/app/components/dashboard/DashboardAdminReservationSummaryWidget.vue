<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — 予約サマリウィジェット（ADMIN_TEAM_RESERVATIONS）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2①
 *
 * - チームスコープ専用（組織には予約 API が無い）。`getAdminReservationSummary(teamSlug)` を消費する。
 * - 表示: 承認待ち件数（導線）/ 本日の予約数。
 * - 導線: /teams/[slug]/admin/reservations。
 * - API 取得失敗は握りつぶさず注記で表示（症状を隠さない）。
 */
import type { AdminReservationSummary } from '~/types/admin-dashboard-widgets'

const props = defineProps<{
  /** チームの slug（getAdminReservationSummary のパス・ハブ導線に使用）。 */
  slug: string
}>()

const { getAdminReservationSummary } = useScopeTabApi()

const summary = ref<AdminReservationSummary | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/** 導線先: 予約管理ページ。 */
const reservationsRoute = computed(() => `/teams/${props.slug}/admin/reservations`)

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    summary.value = await getAdminReservationSummary(props.slug)
    loaded.value = true
  } catch (e) {
    console.error('[DashboardAdminReservationSummaryWidget] fetch failed', e)
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
    <SectionCard :title="$t('adminConsole.lens.widgets.reservations')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.reservations.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <NuxtLink
          :to="reservationsRoute"
          class="flex items-center justify-between text-left hover:text-primary"
          data-testid="admin-reservations-link"
        >
          <span class="flex items-center gap-2">
            <i class="pi pi-calendar-clock text-2xl text-primary" aria-hidden="true" />
            <span class="text-2xl font-bold" data-testid="admin-reservations-pending">
              {{ summary?.pendingCount ?? 0 }}
            </span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>

        <ul class="flex flex-col gap-1 text-xs text-surface-600 dark:text-surface-400">
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.reservations.pending') }}</span>
            <span
              class="font-medium"
              :class="(summary?.pendingCount ?? 0) > 0 ? 'text-amber-600 dark:text-amber-400' : ''"
              data-testid="admin-reservations-pending-count"
            >
              {{ summary?.pendingCount ?? 0 }}
            </span>
          </li>
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.reservations.today') }}</span>
            <span class="font-medium" data-testid="admin-reservations-today">
              {{ summary?.todayCount ?? 0 }}
            </span>
          </li>
        </ul>
      </div>
    </SectionCard>
  </div>
</template>
