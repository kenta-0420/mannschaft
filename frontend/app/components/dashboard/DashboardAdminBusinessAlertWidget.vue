<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — 業務アラートウィジェット（ADMIN_TEAM_ALERT / ADMIN_ORG_ALERT）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2⑤ §2.3⑤
 *
 * - team/org 両スコープ対応。`getAdminBusinessAlert(scopeType, slug)` を消費する。
 * - 表示: 新規予約 N 件 / 未読問い合わせ N 件。
 *   - 組織スコープは new_reservations=0 固定（§2.3⑤：組織に予約ウィジェット無し）。
 * - 承認待ち（③）と二重計上しない（§3 方針）。
 * - API 取得失敗は握りつぶさず注記で表示（症状を隠さない）。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'
import type { AdminBusinessAlert } from '~/types/admin-dashboard-widgets'

const props = defineProps<{
  scopeType: ScopeTabType
  /** スコープの slug（getAdminBusinessAlert のパス・ハブ導線に使用）。 */
  slug: string
}>()

const { getAdminBusinessAlert } = useScopeTabApi()

const summary = ref<AdminBusinessAlert | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/** 導線先: 予約管理 or 問い合わせページ（スコープ別）。 */
const reservationsRoute = computed(() => {
  const base = props.scopeType === 'TEAM' ? 'teams' : 'organizations'
  return `/${base}/${props.slug}/admin/reservations`
})

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    summary.value = await getAdminBusinessAlert(props.scopeType, props.slug)
    loaded.value = true
  } catch (e) {
    console.error('[DashboardAdminBusinessAlertWidget] fetch failed', e)
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
    <SectionCard :title="$t('adminConsole.lens.widgets.alert')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.alert.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <NuxtLink
          :to="reservationsRoute"
          class="flex items-center justify-between text-left hover:text-primary"
          :data-testid="`admin-alert-link-${scopeType}`"
        >
          <span class="flex items-center gap-2">
            <i class="pi pi-bell text-2xl text-primary" aria-hidden="true" />
            <span
              class="text-2xl font-bold"
              :data-testid="`admin-alert-total-${scopeType}`"
            >
              {{ (summary?.newReservations ?? 0) + (summary?.unreadInquiries ?? 0) }}
            </span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>

        <ul class="flex flex-col gap-1 text-xs text-surface-600 dark:text-surface-400">
          <!-- 組織スコープは new_reservations=0 固定のため TEAM のみ表示 -->
          <li
            v-if="scopeType === 'TEAM'"
            class="flex items-center justify-between"
          >
            <span>{{ $t('adminConsole.lens.alert.newReservations') }}</span>
            <span class="font-medium" :data-testid="`admin-alert-reservations-${scopeType}`">
              {{ summary?.newReservations ?? 0 }}
            </span>
          </li>
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.alert.unreadInquiries') }}</span>
            <span class="font-medium" :data-testid="`admin-alert-inquiries-${scopeType}`">
              {{ summary?.unreadInquiries ?? 0 }}
            </span>
          </li>
        </ul>
      </div>
    </SectionCard>
  </div>
</template>
