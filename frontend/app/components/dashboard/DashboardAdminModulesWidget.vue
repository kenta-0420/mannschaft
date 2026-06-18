<script setup lang="ts">
/**
 * F10.1.1 L1 管理者レンズ — モジュールウィジェット（ADMIN_TEAM_MODULES）。
 *
 * 設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2⑦
 *
 * - チームスコープ専用（設計書 §2.2⑦ に記載。組織に対応するウィジェットはない）。
 * - 既存 `useAdminDashboardApi().listModules('team', slug)` を消費する（新規 EP 不要）。
 * - 表示: 有効 N / 全 M（モジュール一覧の enabled 判定を FE で集計）。
 * - 導線: `/teams/[slug]/admin/settings/modules`。
 * - API 取得失敗は握りつぶさず注記で表示（症状を隠さない）。
 */

const props = defineProps<{
  /** チームの slug（listModules のパス・ハブ導線に使用）。 */
  slug: string
}>()

const { listModules } = useAdminDashboardApi()

interface ModuleItem {
  moduleId: string
  name: string
  enabled: boolean
}

const modules = ref<ModuleItem[]>([])
const loading = ref(false)
const fetchFailed = ref(false)
const loaded = ref(false)

const rootEl = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

/** 有効なモジュール数。 */
const enabledCount = computed(() => modules.value.filter((m) => m.enabled).length)
/** 全モジュール数。 */
const totalCount = computed(() => modules.value.length)

/** 導線先: モジュール設定ページ。 */
const modulesRoute = computed(() => `/teams/${props.slug}/admin/settings/modules`)

async function fetchModules() {
  if (loaded.value || loading.value) return
  loading.value = true
  fetchFailed.value = false
  try {
    modules.value = await listModules('team', props.slug)
    loaded.value = true
  } catch (e) {
    console.error('[DashboardAdminModulesWidget] fetch failed', e)
    fetchFailed.value = true
  } finally {
    loading.value = false
  }
}

watch(
  () => props.slug,
  () => {
    loaded.value = false
    modules.value = []
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
        fetchModules()
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
    <SectionCard :title="$t('adminConsole.lens.widgets.modules')">
      <div v-if="loading" class="py-4 text-center text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" />
      </div>

      <Message v-else-if="fetchFailed" severity="warn" :closable="false">
        {{ $t('adminConsole.lens.modules.fetchFailed') }}
      </Message>

      <div v-else-if="loaded" class="flex flex-col gap-3">
        <NuxtLink
          :to="modulesRoute"
          class="flex items-center justify-between text-left hover:text-primary"
          data-testid="admin-modules-link"
        >
          <span class="flex items-center gap-2">
            <i class="pi pi-th-large text-2xl text-primary" aria-hidden="true" />
            <span class="text-2xl font-bold" data-testid="admin-modules-enabled">
              {{ enabledCount }}
            </span>
          </span>
          <i class="pi pi-chevron-right text-xs" aria-hidden="true" />
        </NuxtLink>

        <p class="text-xs text-surface-600 dark:text-surface-400">
          {{ $t('adminConsole.lens.modules.summary', { enabled: enabledCount, total: totalCount }) }}
        </p>
      </div>
    </SectionCard>
  </div>
</template>
