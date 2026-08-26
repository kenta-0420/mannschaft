<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — メンバー統計ウィジェット（ADMIN_TEAM_MEMBERS / ADMIN_ORG_MEMBERS）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2④ §2.3④
 *
 * - team/org 両スコープ対応。`getAdminMemberStats(scopeType, slug)` を消費する。
 * - 表示: 総数（導線）/ アクティブ / 今月新規。
 * - 導線: /{base}/[slug]/member-cards（既存正本・P4 A-1方針。/admin/members 整備後に切替予定）。
 * - API 取得失敗は握りつぶさず注記で表示（症状を隠さない）。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'
import type { AdminMemberStats } from '~/types/admin-dashboard-widgets'

const props = defineProps<{
  scopeType: ScopeTabType
  /** スコープの slug（getAdminMemberStats のパス・ハブ導線に使用）。 */
  slug: string
}>()

const { getAdminMemberStats } = useScopeTabApi()

const summary = ref<AdminMemberStats | null>(null)
const loading = ref(false)
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/**
 * 導線先: メンバー管理ページ（既存正本ルート）。
 * 設計方針（P4 A-1）: `/admin/members` はまだ存在しないため、
 * ハブカードと同様に既存の正本ルート `/{base}/[slug]/member-cards` へ直結する。
 */
const membersRoute = computed(() => {
  const base = props.scopeType === 'TEAM' ? 'teams' : 'organizations'
  return `/${base}/${props.slug}/member-cards`
})

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    summary.value = await getAdminMemberStats(props.scopeType, props.slug)
    loaded.value = true
  } catch (e) {
    console.error('[DashboardAdminMemberStatsWidget] fetch failed', e)
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
    <SectionCard :title="$t('adminConsole.lens.widgets.members')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.members.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <NuxtLink
          :to="membersRoute"
          class="flex items-center justify-between text-left hover:text-primary"
          :data-testid="`admin-members-link-${scopeType}`"
        >
          <span class="flex items-center gap-2">
            <i class="pi pi-users text-2xl text-primary" aria-hidden="true" />
            <span
              class="text-2xl font-bold"
              :data-testid="`admin-members-total-${scopeType}`"
            >
              {{ summary?.totalCount ?? 0 }}
            </span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>

        <ul class="flex flex-col gap-1 text-xs text-surface-600 dark:text-surface-400">
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.members.total') }}</span>
            <span class="font-medium" :data-testid="`admin-members-total-count-${scopeType}`">
              {{ summary?.totalCount ?? 0 }}
            </span>
          </li>
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.members.active') }}</span>
            <span class="font-medium" :data-testid="`admin-members-active-${scopeType}`">
              {{ summary?.activeCount ?? 0 }}
            </span>
          </li>
          <li class="flex items-center justify-between">
            <span>{{ $t('adminConsole.lens.members.newThisMonth') }}</span>
            <span class="font-medium" :data-testid="`admin-members-new-${scopeType}`">
              {{ summary?.newThisMonthCount ?? 0 }}
            </span>
          </li>
        </ul>
      </div>
    </SectionCard>
  </div>
</template>
