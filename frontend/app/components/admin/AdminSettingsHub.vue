<script setup lang="ts">
import { parseScopeRoute } from '~/composables/useScopeRouteSync'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  slug: string
}>()

const { t } = useI18n()
const nuxtApp = useNuxtApp()
const route = useRoute()
const { roleName, loadPermissions } = useRoleAccess(props.scopeType, toRef(props, 'slug'))
const scopeStore = useScopeStore()
const teamStore = useTeamStore()
const organizationStore = useOrganizationStore()
const { getTeamModules } = useModuleApi()
const { getOrganizationModules } = useOrganizationModuleApi()
const loading = ref(true)
const permissionError = shallowRef<unknown>()
const moduleError = shallowRef<unknown>()
const scopeError = shallowRef<unknown>()
const enabledModules = ref<string[]>([])
let active = true
const base = computed(() => `/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.slug}`)
// security/03 §3.5: 新しいスコープ運営 UI は ADMIN 等値。既存 helper の SYS 許可は変更しない。
const isScopeAdmin = computed(() => roleName.value === 'ADMIN')
// /admin配下は親shellがmetadataを取得しない。既存resolverが確認する本人所属を正本にする。
const membership = computed(() => props.scopeType === 'team'
  ? teamStore.myTeams.find(item => item.slug === props.slug)
  : organizationStore.myOrganizations.find(item => item.slug === props.slug))
const identityResolved = computed(() => Number.isSafeInteger(membership.value?.id) && (membership.value?.id ?? 0) > 0)
const membershipLoading = computed(() => props.scopeType === 'team' ? teamStore.loading : organizationStore.loading)
const routeMatches = computed(() => {
  const parsed = parseScopeRoute(route.path)
  return parsed?.scopeType === props.scopeType && parsed.slug === props.slug
})
const globalReady = computed(() =>
  identityResolved.value
  && !membershipLoading.value
  && routeMatches.value
  && scopeStore.current.type === props.scopeType
  && scopeStore.current.id === String(membership.value?.id),
)
const scopedLinks = computed(() => {
  const settings = props.scopeType === 'team'
    ? [
        { key: 'shift', path: 'settings/shift' },
        { key: 'faq', path: 'settings/faq-settings' },
        { key: 'public', path: 'settings/public-settings' },
        { key: 'care', path: 'settings/care-overrides' },
        { key: 'todoLabels', path: 'settings/todo-status-labels' },
      ]
    : [
        { key: 'faq', path: 'settings/faq-settings' },
        { key: 'notificationCredits', path: 'settings/notification-credits' },
        { key: 'public', path: 'settings/public-settings' },
        { key: 'todoLabels', path: 'settings/todo-status-labels' },
      ]
  return [...settings, { key: 'modules', path: 'modules' }]
})

async function loadModules() {
  moduleError.value = undefined
  enabledModules.value = []
  try {
    const modules = props.scopeType === 'team'
      ? (await getTeamModules(props.slug)).data
      : await getOrganizationModules(props.slug)
    enabledModules.value = modules.filter(module => module.isEnabled).map(module => module.moduleSlug)
  }
  catch (error) {
    moduleError.value = error
  }
}

async function synchronizeScope() {
  scopeError.value = undefined
  // 本人所属取得前はidentity未確定でもresolverを開始し、取得後のguardで書込みを判定する。
  if (!active || !routeMatches.value || !isScopeAdmin.value) return
  const path = base.value
  const slug = props.slug
  const scopeType = props.scopeType
  const isRouteCurrent = () => active
    && props.slug === slug
    && props.scopeType === scopeType
    && isScopeAdmin.value
    && routeMatches.value
  const isCurrent = () => isRouteCurrent() && identityResolved.value && !membershipLoading.value
  try {
    // 所属取得失敗後の明示再試行でも再取得できるよう、今回の同期1回に既存resolverを使う。
    await nuxtApp.runWithContext(() => useScopeRouteSync().syncFromPath(path, isCurrent))
    if (!isRouteCurrent()) return
    if (!globalReady.value) throw new Error('Scope could not be confirmed')
  }
  catch (error) {
    scopeError.value = error
  }
}

async function load() {
  loading.value = true
  permissionError.value = undefined
  try {
    const result = await loadPermissions()
    if (!active) return
    if (!result.ok) throw result.error
    if (isScopeAdmin.value) await Promise.all([loadModules(), synchronizeScope()])
  }
  catch (error) {
    permissionError.value = error ?? new Error('Permissions could not be loaded')
  }
  finally {
    loading.value = false
  }
}

function guardGlobalNavigation(event: MouseEvent, requiresPayment = false) {
  if (active && !loading.value && !permissionError.value && !scopeError.value
    && globalReady.value && isScopeAdmin.value
    && (!requiresPayment || (!moduleError.value && enabledModules.value.includes('payment')))) return
  event.preventDefault()
}

watch(() => membership.value?.id, () => {
  if (!loading.value && isScopeAdmin.value) void synchronizeScope()
})
onMounted(() => { void load() })
onBeforeUnmount(() => { active = false })
</script>

<template>
  <div class="space-y-5 p-4 md:p-6">
    <PageHeader :title="t('adminConsole.settingsHub.title')" :back-to="`${base}/admin`" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="permissionError" :error="permissionError" @retry="load" />
    <DashboardErrorState v-else-if="!isScopeAdmin" kind="forbidden" :show-retry="false" />
    <template v-else>
      <SectionCard :title="t('adminConsole.settingsHub.scopeSettings')">
        <ul class="grid gap-2 sm:grid-cols-2">
          <li v-for="link in scopedLinks" :key="link.key">
            <NuxtLink
              :to="`${base}/${link.path}`"
              class="flex min-h-11 items-center rounded-lg px-3 py-2 text-primary-600 hover:bg-surface-100 focus-visible:outline focus-visible:outline-2 dark:hover:bg-surface-700"
              :data-testid="`setting-${link.key}`"
            >
              {{ t(`adminConsole.settingsHub.items.${link.key}`) }}
            </NuxtLink>
          </li>
        </ul>
      </SectionCard>
      <SectionCard :title="t('adminConsole.settingsHub.integrations')">
        <DashboardErrorState v-if="scopeError" :error="scopeError" testid="settings-scope-error" @retry="synchronizeScope" />
        <p v-else-if="!globalReady" role="status" class="text-sm text-surface-600 dark:text-surface-300">
          {{ t('adminConsole.settingsHub.confirmingScope') }}
        </p>
        <ul v-else class="grid gap-2 sm:grid-cols-2">
          <li>
            <NuxtLink to="/admin/line-settings" class="flex min-h-11 items-center rounded-lg px-3 py-2 text-primary-600 hover:bg-surface-100 focus-visible:outline focus-visible:outline-2 dark:hover:bg-surface-700" data-testid="setting-line" @click="guardGlobalNavigation">
              {{ t('adminConsole.settingsHub.items.line') }}
            </NuxtLink>
          </li>
          <li v-if="enabledModules.includes('payment')">
            <NuxtLink to="/admin/receipt-settings" class="flex min-h-11 items-center rounded-lg px-3 py-2 text-primary-600 hover:bg-surface-100 focus-visible:outline focus-visible:outline-2 dark:hover:bg-surface-700" data-testid="setting-receipts" @click="guardGlobalNavigation($event, true)">
              {{ t('adminConsole.settingsHub.items.receipts') }}
            </NuxtLink>
          </li>
        </ul>
        <DashboardErrorState v-if="moduleError" :error="moduleError" testid="settings-module-error" @retry="loadModules" />
      </SectionCard>
    </template>
  </div>
</template>
