<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — 承認待ちウィジェット（ADMIN_*_APPROVALS）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2① ②③ / §6
 *        / 03_admin_action_required_api.md §3 / 05_decisions.md §9
 *
 * - `useScopeTabApi.getAdminActionRequired(scopeType, slug, previewSize)`（P2b 実装済）を消費する。
 * - ビューポート進入時に遅延取得（ScopeActionRequiredWidget §5 に倣う）。
 * - **degraded（集計失敗）を 0 件と混同しない**（03 §4.3 / 05 §9）。一部ドメインが degraded のときは
 *   「一部集計できず」を明示する。API 自体の取得失敗は握りつぶさずバッジ非表示＋注記（症状を隠さない）。
 * - 導線は P2a ハブ（`/teams|organizations/[slug]/admin`）の承認待ちセクションへ（L1 は読み取り専用・実操作は L3）。
 */
import type { ScopeTabType } from '~/types/dashboard-scope'
import type { AdminActionRequiredSummary } from '~/types/admin-action-required'

const props = defineProps<{
  scopeType: ScopeTabType
  /** スコープの slug（getAdminActionRequired のパス・ハブ導線に使用）。 */
  slug: string
}>()

const { getAdminActionRequired } = useScopeTabApi()

const summary = ref<AdminActionRequiredSummary | null>(null)
const loading = ref(false)
/** API 自体の取得失敗（degraded とは区別する。症状を隠さず注記で表示）。 */
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/** いずれかのドメインが degraded（集計失敗）か。 */
const hasDegraded = computed(() =>
  summary.value?.domains.some(d => d.degraded) ?? false,
)
/** total_pending（degraded ドメインは加算されない・03 §3.3）。 */
const totalPending = computed(() => summary.value?.totalPending ?? 0)

/** L2 ハブの承認待ちセクションへの導線。 */
const consoleRoute = computed(() => {
  const base = props.scopeType === 'TEAM' ? 'teams' : 'organizations'
  return `/${base}/${props.slug}/admin`
})

async function fetchSummary() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    // L1 グランスは件数＋ドメイン内訳のみで足りるため preview_size=0（プレビュー不要）。
    summary.value = await getAdminActionRequired(props.scopeType, props.slug, 0)
    loaded.value = true
  } catch (e) {
    // 握り潰さない。取得失敗を 0 件と偽らず、注記で穏当に表示する（症状を隠さない）。
    console.error('[DashboardAdminApprovalsWidget] fetch failed', e)
    fetchFailed.value = true
  } finally {
    loading.value = false
  }
}

// slug が変わったら再取得対象に戻す。
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
    <SectionCard :title="$t('adminConsole.lens.widgets.approvals')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <!-- API 自体の取得失敗: 握りつぶさず注記で表示（0 件と偽らない）。 -->
      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.approvals.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <NuxtLink
          :to="consoleRoute"
          class="flex items-center justify-between text-left hover:text-primary"
        >
          <span class="flex items-center gap-2">
            <i class="pi pi-inbox text-2xl text-primary" aria-hidden="true" />
            <span
              v-if="totalPending > 0"
              class="text-2xl font-bold"
              :data-testid="`admin-approvals-total-${scopeType}`"
            >{{ totalPending }}</span>
            <span v-else class="text-sm text-surface-500">
              {{ $t('adminConsole.lens.approvals.empty') }}
            </span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>

        <!-- 一部ドメインが集計失敗（degraded）のとき、total を「正確な総数」と誤認させない注記。 -->
        <p
          v-if="hasDegraded"
          class="inline-flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400"
          :data-testid="`admin-approvals-degraded-${scopeType}`"
        >
          <i class="pi pi-exclamation-triangle text-[0.7rem]" aria-hidden="true" />
          {{ $t('adminConsole.lens.approvals.degraded') }}
        </p>

        <!-- ドメイン別内訳（degraded を 0 件と区別して明示）。 -->
        <ul class="flex flex-col gap-1 text-xs text-surface-600 dark:text-surface-400">
          <li
            v-for="d in summary?.domains ?? []"
            :key="d.domain"
            class="flex items-center justify-between"
          >
            <span>{{ $t(`adminConsole.lens.approvals.domain.${d.domain}`) }}</span>
            <span v-if="d.degraded" class="inline-flex items-center gap-1 text-amber-600 dark:text-amber-400">
              <i class="pi pi-exclamation-triangle text-[0.7rem]" aria-hidden="true" />
              {{ $t('adminConsole.lens.approvals.degraded') }}
            </span>
            <span v-else class="font-medium">{{ d.pendingCount }}</span>
          </li>
        </ul>
      </div>
    </SectionCard>
  </div>
</template>
