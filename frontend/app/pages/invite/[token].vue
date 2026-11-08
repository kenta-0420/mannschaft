<script setup lang="ts">
import type { components } from '~/types/generated'
import type { ScopeType } from '~/types/scopeFolder'

definePageMeta({ layout: 'auth' })

const route = useRoute()
const api = useApi()
const authStore = useAuthStore()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { t, te } = useI18n()
const token = computed(() => String(route.params.token))
type InvitePreview = components['schemas']['InvitePreviewResponse']
const preview = ref<InvitePreview | null>(null)
const loading = ref(true)
const joining = ref(false)
const loadError = shallowRef<unknown>(null)
const invalid = ref(false)
const selectedFolderId = ref<number | null>(null)
const scopeType = computed<ScopeType | null>(() => {
  const value = preview.value?.targetType
  return value === 'TEAM' || value === 'ORGANIZATION' ? value : null
})
const targetName = computed(() => preview.value?.targetName || t('auth.invite.unknown_target'))
const typeLabel = computed(() => t(`auth.invite.types.${scopeType.value}`))
const roleLabel = computed(() => {
  const role = preview.value?.roleName
  return role ? (te(`role.${role}`) ? t(`role.${role}`) : role) : t('auth.invite.unknown_role')
})

async function fetchPreview() {
  if (!authStore.isAuthenticated) {
    loading.value = false
    return
  }
  loading.value = true
  loadError.value = null
  invalid.value = false
  preview.value = null
  try {
    const result = await api<components['schemas']['ApiResponseInvitePreviewResponse']>(
      `/api/v1/invite/${token.value}`,
    )
    if (result.data?.valid === false) {
      invalid.value = true
      return
    }
    if (
      result.data?.valid !== true ||
      !['TEAM', 'ORGANIZATION'].includes(result.data.targetType ?? '')
    ) {
      throw new Error('Invalid invite preview response')
    }
    preview.value = result.data
  } catch (err: unknown) {
    const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
    if (code === 'ROLE_002') invalid.value = true
    else {
      loadError.value = err
      handleApiError(err, t('auth.invite.load_context'))
    }
  } finally {
    loading.value = false
  }
}

async function joinWithToken() {
  if (joining.value || !authStore.isAuthenticated || !preview.value?.valid || !scopeType.value)
    return
  joining.value = true
  try {
    await api(`/api/v1/invite/${token.value}/join`, {
      method: 'POST',
      body: selectedFolderId.value != null ? { folderId: selectedFolderId.value } : {},
    })
    notification.success(t('auth.invite.joined', { type: typeLabel.value }))
    await navigateTo('/dashboard')
  } catch (err: unknown) {
    handleApiError(err, t('auth.invite.join_context'))
  } finally {
    joining.value = false
  }
}

function goToAuth(path: '/login' | '/register') {
  return navigateTo({ path, query: { redirect: route.fullPath } })
}

onMounted(fetchPreview)
</script>

<template>
  <div class="flex min-h-screen items-center justify-center p-4">
    <PageLoading v-if="loading" />
    <SectionCard v-else-if="!authStore.isAuthenticated" class="w-full max-w-md text-center">
      <h2 class="mb-2 text-xl font-bold">{{ t('auth.invite.title') }}</h2>
      <p class="mb-6 text-surface-500">{{ t('auth.invite.login_hint') }}</p>
      <Button
        :label="t('auth.invite.login')"
        icon="pi pi-sign-in"
        class="w-full"
        @click="goToAuth('/login')"
      />
      <Button
        :label="t('auth.invite.register')"
        icon="pi pi-user-plus"
        severity="secondary"
        class="mt-2 w-full"
        @click="goToAuth('/register')"
      />
    </SectionCard>
    <SectionCard v-else-if="loadError" class="w-full max-w-md">
      <DashboardErrorState :error="loadError" :show-retry="true" @retry="fetchPreview" />
    </SectionCard>
    <SectionCard
      v-else-if="invalid"
      class="w-full max-w-md text-center"
      data-testid="invite-invalid"
    >
      <i class="pi pi-times-circle mb-4 text-5xl text-red-500" />
      <h2 class="mb-2 text-xl font-bold">{{ t('auth.invite.invalid_title') }}</h2>
      <p class="mb-6 text-surface-500">{{ t('auth.invite.invalid_hint') }}</p>
      <Button :label="t('auth.invite.home')" icon="pi pi-home" @click="navigateTo('/')" />
    </SectionCard>
    <SectionCard v-else-if="preview && scopeType" class="w-full max-w-md text-center">
      <Avatar :label="targetName.charAt(0)" shape="circle" size="xlarge" class="mb-4" />
      <Tag :value="typeLabel" severity="info" class="mb-2" />
      <h2 class="mb-4 text-xl font-bold">{{ targetName }}</h2>
      <div class="mb-6 flex items-center justify-between gap-2 text-sm">
        <span class="text-surface-500">{{ t('auth.invite.role') }}</span>
        <span class="font-medium">{{ roleLabel }}</span>
      </div>
      <InviteFolderPicker v-model="selectedFolderId" :scope-type="scopeType" class="mb-4" />
      <Button
        :label="t('auth.invite.join')"
        icon="pi pi-check"
        class="w-full"
        :loading="joining"
        data-testid="invite-join-button"
        @click="joinWithToken"
      />
    </SectionCard>
  </div>
</template>
