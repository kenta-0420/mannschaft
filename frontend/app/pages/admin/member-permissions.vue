<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

type ApiScopeType = 'TEAM' | 'ORGANIZATION'
interface MemberPermissionSetting {
  name: string
  enabled: boolean
  inherited?: boolean
}
interface MemberPermissionsResponse {
  scopeType: ApiScopeType
  scopeId: number
  roleName: 'MEMBER'
  permissions: MemberPermissionSetting[]
}

const api = useApi()
const scopeStore = useScopeStore()
const teamStore = useTeamStore()
const organizationStore = useOrganizationStore()
const { t } = useI18n()
const { success, error: showError } = useNotification()
const scopeType = computed<ApiScopeType | null>(() => {
  if (scopeStore.current.type === 'team') return 'TEAM'
  if (scopeStore.current.type === 'organization') return 'ORGANIZATION'
  return null
})
const scopeId = computed(() => {
  const value = Number(scopeStore.current.id)
  return Number.isSafeInteger(value) && value > 0 ? value : null
})
const hasValidScope = computed(() => scopeType.value !== null && scopeId.value !== null)
const permissions = ref<MemberPermissionSetting[]>([])
const loading = ref(false)
const loadFailed = ref(false)
const accessDenied = ref(false)
const saving = ref(false)
const loadedScopeType = ref<ApiScopeType | null>(null)
const loadedScopeId = ref<number | null>(null)
let loadRequestId = 0
const matchesLoadedScope = computed(
  () => loadedScopeType.value === scopeType.value && loadedScopeId.value === scopeId.value,
)

function permissionLabel(name: string): string {
  return t(`memberPermissions.permissions.${name}`)
}

async function load() {
  const requestId = ++loadRequestId
  const requestedScopeType = scopeType.value
  const requestedScopeId = scopeId.value
  permissions.value = []
  loadedScopeType.value = null
  loadedScopeId.value = null
  loadFailed.value = false
  accessDenied.value = false
  if (requestedScopeType === null || requestedScopeId === null) return
  loading.value = true
  try {
    let role: string | undefined
    if (requestedScopeType === 'TEAM') {
      if (teamStore.myTeams.length === 0) await teamStore.fetchMyTeams()
      role = teamStore.myTeams.find((team) => team.id === requestedScopeId)?.role
    } else {
      if (organizationStore.myOrganizations.length === 0) {
        await organizationStore.fetchMyOrganizations()
      }
      role = organizationStore.myOrganizations.find(
        (organization) => organization.id === requestedScopeId,
      )?.role
    }
    if (
      requestId !== loadRequestId ||
      requestedScopeType !== scopeType.value ||
      requestedScopeId !== scopeId.value
    )
      return
    if (role !== 'ADMIN') {
      accessDenied.value = true
      return
    }
    const response = await api<{ data: MemberPermissionsResponse }>(
      '/api/v1/admin/member-permissions',
      {
        query: { scopeType: requestedScopeType, scopeId: requestedScopeId },
      },
    )
    if (
      requestId !== loadRequestId ||
      requestedScopeType !== scopeType.value ||
      requestedScopeId !== scopeId.value
    )
      return
    permissions.value = response.data.permissions
    loadedScopeType.value = requestedScopeType
    loadedScopeId.value = requestedScopeId
  } catch {
    if (requestId === loadRequestId) loadFailed.value = true
  } finally {
    if (requestId === loadRequestId) loading.value = false
  }
}

async function save() {
  const requestedScopeType = scopeType.value
  const requestedScopeId = scopeId.value
  if (
    !hasValidScope.value ||
    !matchesLoadedScope.value ||
    requestedScopeType === null ||
    requestedScopeId === null ||
    loadFailed.value ||
    accessDenied.value ||
    permissions.value.length === 0
  )
    return
  saving.value = true
  try {
    const response = await api<{ data: MemberPermissionsResponse }>(
      '/api/v1/admin/member-permissions',
      {
        method: 'PUT',
        query: { scopeType: requestedScopeType, scopeId: requestedScopeId },
        body: { permissions: permissions.value.map(({ name, enabled }) => ({ name, enabled })) },
      },
    )
    if (requestedScopeType === scopeType.value && requestedScopeId === scopeId.value) {
      permissions.value = response.data.permissions
      success(t('memberPermissions.saveSuccess'))
    }
  } catch {
    showError(t('memberPermissions.saveFailed'))
  } finally {
    saving.value = false
  }
}

watch([scopeType, scopeId], load)
onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <div class="mb-6 flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
      <PageHeader :title="t('memberPermissions.title')">
        <p class="text-sm text-surface-500">{{ t('memberPermissions.description') }}</p>
      </PageHeader>
      <Button
        :label="t('button.save')"
        icon="pi pi-check"
        class="min-h-11 shrink-0"
        :loading="saving"
        :disabled="
          !hasValidScope ||
          !matchesLoadedScope ||
          loadFailed ||
          accessDenied ||
          loading ||
          permissions.length === 0
        "
        @click="save"
      />
    </div>

    <DashboardErrorState
      v-if="!hasValidScope"
      :message="t('memberPermissions.invalidScope')"
      :show-retry="false"
      testid="member-permissions-invalid-scope"
    />
    <PageLoading v-else-if="loading" />
    <DashboardErrorState
      v-else-if="accessDenied"
      :message="t('memberPermissions.accessDenied')"
      :show-retry="false"
      testid="member-permissions-access-denied"
    />
    <DashboardErrorState
      v-else-if="loadFailed"
      :message="t('memberPermissions.loadFailed')"
      testid="member-permissions-load-error"
      @retry="load"
    />
    <SectionCard v-else :title="t('memberPermissions.sectionTitle')">
      <div class="grid gap-3 sm:grid-cols-2">
        <div
          v-for="permission in permissions"
          :key="permission.name"
          class="flex min-h-11 min-w-0 items-center justify-between gap-3 rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-900"
        >
          <div class="min-w-0">
            <p class="break-words text-sm">{{ permissionLabel(permission.name) }}</p>
            <p v-if="permission.inherited" class="text-xs text-surface-500">
              {{ t('memberPermissions.inherited') }}
            </p>
          </div>
          <ToggleSwitch
            v-model="permission.enabled"
            :disabled="saving"
            :aria-label="permissionLabel(permission.name)"
          />
        </div>
      </div>
    </SectionCard>
  </div>
</template>
