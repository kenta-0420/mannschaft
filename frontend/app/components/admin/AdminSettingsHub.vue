<script setup lang="ts">
import { matchGateKey } from '~/constants/featureGates'
import { getLoadErrorHttpStatus } from '~/utils/loadError'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  slug: string
}>()

const { t } = useI18n()
const slug = computed(() => props.slug)
const { roleName, isScopeAdmin: isAdmin, loadPermissions } = useRoleAccess(props.scopeType, slug)
const { getAdminActionRequired } = useScopeTabApi()
const flags = useFeatureFlagStore()
const loading = ref(true)
const loadError = shallowRef<unknown>(null)
const confirmedScopeManager = ref(false)
const isSystemAdmin = computed(() => roleName.value === 'SYSTEM_ADMIN')
const scopeManagerAccess = computed(
  () => isAdmin.value || roleName.value === 'DEPUTY_ADMIN' || confirmedScopeManager.value,
)
const hubAccess = computed(() => scopeManagerAccess.value || isSystemAdmin.value)
const base = computed(
  () => `/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.slug}`,
)

interface SettingsLink {
  path: string
  title: string
  adminOnly?: boolean
  sysAllowed?: boolean
  readOnly?: boolean
}

const links = computed(() => {
  const settings: SettingsLink[] = [
    { path: 'faq-settings', title: 'faq.settings.title', adminOnly: true, sysAllowed: true },
    { path: 'public-settings', title: 'public.publicSettings.title', sysAllowed: true },
    {
      path: 'todo-status-labels',
      title: 'todo.statusLabel.management.title',
      adminOnly: props.scopeType === 'organization',
      readOnly: props.scopeType === 'team' && !isAdmin.value,
    },
  ]
  if (props.scopeType === 'team') {
    settings.unshift({ path: 'shift', title: 'teamShiftSettings.title' })
  } else {
    settings.push({
      path: 'notification-credits',
      title: 'notificationCredit.title',
      adminOnly: true,
    })
  }
  return settings.filter((link) => {
    const systemException = isSystemAdmin.value && link.sysAllowed
    if (!scopeManagerAccess.value && !systemException) return false
    if (link.adminOnly && !isAdmin.value && !systemException) return false
    const gate = matchGateKey(`${base.value}/settings/${link.path}`)
    return gate === null || (flags.publicLoaded && flags.isEnabled(gate))
  })
})

let loadSequence = 0
async function loadPage() {
  const sequence = ++loadSequence
  const requestedSlug = props.slug
  const isCurrent = () => sequence === loadSequence && requestedSlug === props.slug
  loading.value = true
  loadError.value = null
  confirmedScopeManager.value = false
  try {
    const result = await loadPermissions()
    if (!isCurrent()) return
    if (!result.ok) throw result.error
    if (roleName.value === 'SYSTEM_ADMIN' && !isAdmin.value) {
      // 表示用ロールから兼任DEPUTYを推測せず、既存API入口の正準スコープ認可で確認する。
      try {
        await getAdminActionRequired(
          props.scopeType === 'team' ? 'TEAM' : 'ORGANIZATION',
          requestedSlug,
          0,
        )
        if (!isCurrent()) return
        confirmedScopeManager.value = true
      } catch (error) {
        if (!isCurrent()) return
        if (getLoadErrorHttpStatus(error) !== 403) throw error
      }
    }
    if (scopeManagerAccess.value && props.scopeType === 'team' && !flags.publicLoaded) {
      await flags.loadPublicFlags()
    }
  } catch (error) {
    if (isCurrent()) loadError.value = error
  } finally {
    if (isCurrent()) loading.value = false
  }
}

onMounted(loadPage)
watch(slug, loadPage)
</script>

<template>
  <div class="space-y-6 p-4 md:p-6">
    <PageHeader :title="t('adminConsole.cards.settings.title')" />
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="loadError" :error="loadError" @retry="loadPage" />
    <DashboardEmptyState
      v-else-if="!hubAccess"
      icon="pi pi-lock"
      :message="t('adminConsole.middleware.accessDeniedBody')"
    />
    <div v-else class="grid gap-4 sm:grid-cols-2">
      <SectionCard v-for="link in links" :key="link.path">
        <NuxtLink
          :to="`${base}/settings/${link.path}`"
          class="flex min-h-11 min-w-0 items-center justify-between gap-3 text-primary-600 hover:underline dark:text-primary-400"
        >
          <span>{{ t(link.title) }}</span>
          <i class="pi pi-arrow-right shrink-0" aria-hidden="true" />
        </NuxtLink>
        <p v-if="link.readOnly" class="mt-2 text-sm text-surface-500">
          {{ t('adminConsole.settingsHub.readOnly') }}
        </p>
      </SectionCard>
    </div>
  </div>
</template>
