<script setup lang="ts">
interface MemberPermissionSetting {
  name: string
  enabled: boolean
  inherited?: boolean
}

interface MemberPermissionsResponse {
  permissions: MemberPermissionSetting[]
}

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId?: number
  slug: string
  roleName: string | null
}>()

const api = useApi()
const { t } = useI18n()
const { success, error: showError } = useNotification()

const visible = ref(false)
const saving = ref(false)
const permissions = ref<MemberPermissionSetting[]>([])
const guideUrl = computed(() =>
  props.scopeType === 'TEAM'
    ? `/teams/${props.slug}/guide`
    : `/organizations/${props.slug}/guide`,
)
const dismissKey = computed(() => `member-permissions-setup:${props.scopeType}:${props.scopeId}`)
let requestId = 0

function dismiss(): void {
  visible.value = false
  sessionStorage.setItem(dismissKey.value, '1')
}

async function checkInitialSetup(): Promise<void> {
  const currentRequestId = ++requestId
  visible.value = false
  permissions.value = []
  if (props.roleName !== 'ADMIN' || !props.scopeId || typeof window === 'undefined') return
  if (sessionStorage.getItem(dismissKey.value) === '1') return

  try {
    const response = await api<{ data: MemberPermissionsResponse }>(
      '/api/v1/admin/member-permissions',
      { query: { scopeType: props.scopeType, scopeId: props.scopeId } },
    )
    if (currentRequestId !== requestId) return
    const settings = response.data.permissions
    if (settings.length !== 3 || !settings.every(setting => setting.inherited)) return
    permissions.value = settings.map(setting => ({ ...setting }))
    visible.value = true
  }
  catch {
    // 初回案内の取得失敗で通常画面は塞がず、管理者へ取得失敗を知らせる。
    if (currentRequestId === requestId) showError(t('scopeGuide.setup.loadFailed'))
  }
}

async function save(): Promise<void> {
  if (!props.scopeId || permissions.value.length !== 3 || saving.value) return
  const requestedScopeType = props.scopeType
  const requestedScopeId = props.scopeId
  saving.value = true
  try {
    await api('/api/v1/admin/member-permissions', {
      method: 'PUT',
      query: { scopeType: requestedScopeType, scopeId: requestedScopeId },
      body: { permissions: permissions.value.map(({ name, enabled }) => ({ name, enabled })) },
    })
    if (props.scopeType === requestedScopeType && props.scopeId === requestedScopeId) {
      success(t('scopeGuide.setup.saved'))
      dismiss()
    }
  }
  catch {
    showError(t('memberPermissions.saveFailed'))
  }
  finally {
    saving.value = false
  }
}

watch(
  () => [props.scopeType, props.scopeId, props.slug, props.roleName] as const,
  checkInitialSetup,
)
onMounted(checkInitialSetup)
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :closable="false"
    :close-on-escape="false"
    :header="t('scopeGuide.setup.title')"
    class="w-[min(95vw,34rem)]"
    data-testid="member-permission-setup"
  >
    <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
      {{ t('scopeGuide.setup.intro') }}
    </p>
    <div class="space-y-3">
      <div
        v-for="permission in permissions"
        :key="permission.name"
        class="flex min-h-11 items-center justify-between gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
      >
        <div class="min-w-0">
          <p class="font-medium">{{ t(`memberPermissions.permissions.${permission.name}`) }}</p>
          <p class="text-sm text-surface-600 dark:text-surface-300">
            {{ t(`scopeGuide.setup.${permission.name}`) }}
          </p>
        </div>
        <ToggleSwitch
          v-model="permission.enabled"
          :disabled="saving"
          :aria-label="t(`memberPermissions.permissions.${permission.name}`)"
        />
      </div>
    </div>
    <p class="mt-4 text-sm text-surface-600 dark:text-surface-300">
      {{ t('scopeGuide.setup.note') }}
      <NuxtLink :to="guideUrl" class="ml-1 text-primary underline" @click="dismiss">
        {{ t('scopeGuide.setup.guideLink') }}
      </NuxtLink>
    </p>
    <template #footer>
      <Button :label="t('scopeGuide.setup.later')" text :disabled="saving" @click="dismiss" />
      <Button :label="t('scopeGuide.setup.save')" icon="pi pi-check" :loading="saving" @click="save" />
    </template>
  </Dialog>
</template>
