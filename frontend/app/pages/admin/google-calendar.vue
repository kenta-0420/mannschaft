<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()
const { getConnectionStatus, connect, disconnect, toggleTeamSync, toggleOrgSync } = useGoogleCalendarApi()

interface ConnectionStatus {
  isConnected: boolean
  email: string | null
  lastSyncedAt: string | null
}

const status = ref<ConnectionStatus | null>(null)
const loading = ref(true)
const syncEnabled = ref(false)
const syncing = ref(false)
const disconnecting = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getConnectionStatus()
    status.value = res.data
  } catch {
    status.value = null
  } finally {
    loading.value = false
  }
}

async function handleConnect() {
  try {
    const res = await connect()
    const url = (res as { data: { authUrl: string } }).data?.authUrl
    if (url) window.location.href = url
  } catch {
    showError('Google Calendar との接続に失敗しました')
  }
}

async function handleDisconnect() {
  if (!confirm('Google Calendar との接続を解除しますか？')) return
  disconnecting.value = true
  try {
    await disconnect()
    success('接続を解除しました')
    await load()
  } catch {
    showError('解除に失敗しました')
  } finally {
    disconnecting.value = false
  }
}

async function toggleSync(enabled: boolean) {
  syncing.value = true
  try {
    if (scopeType.value === 'team') {
      await toggleTeamSync(scopeId.value, { enabled })
    } else {
      await toggleOrgSync(scopeId.value, { enabled })
    }
    syncEnabled.value = enabled
    success(enabled ? '同期を有効にしました' : '同期を無効にしました')
  } catch {
    showError('同期設定の変更に失敗しました')
  } finally {
    syncing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="Googleカレンダー設定" />

    <PageLoading v-if="loading" />

    <div v-else class="flex flex-col gap-4">
      <!-- 接続状態 -->
      <SectionCard title="接続状態">
        <div v-if="status?.isConnected" class="flex items-center justify-between">
          <div>
            <div class="flex items-center gap-2">
              <i class="pi pi-check-circle text-green-500" />
              <span class="font-medium">接続済み</span>
            </div>
            <p class="mt-1 text-sm text-surface-500">{{ status.email }}</p>
          </div>
          <Button
            label="接続解除"
            severity="danger"
            outlined
            size="small"
            :loading="disconnecting"
            @click="handleDisconnect"
          />
        </div>
        <div v-else class="flex items-center justify-between">
          <div>
            <div class="flex items-center gap-2">
              <i class="pi pi-times-circle text-surface-400" />
              <span class="text-surface-600">未接続</span>
            </div>
            <p class="mt-1 text-sm text-surface-400">Google アカウントと連携してカレンダーを同期できます</p>
          </div>
          <Button label="Googleアカウントで接続" icon="pi pi-external-link" @click="handleConnect" />
        </div>
      </SectionCard>

      <!-- 同期設定 -->
      <SectionCard v-if="status?.isConnected" title="同期設定">
        <div class="flex items-center justify-between">
          <div>
            <div class="font-medium">スケジュール同期</div>
            <p class="text-sm text-surface-500">{{ scopeType === 'team' ? 'チーム' : '組織' }}のスケジュールをGoogleカレンダーに同期します</p>
          </div>
          <ToggleSwitch
            :model-value="syncEnabled"
            :disabled="syncing"
            @update:model-value="toggleSync"
          />
        </div>
        <div v-if="status?.lastSyncedAt" class="mt-4 text-xs text-surface-400">
          最終同期: {{ new Date(status.lastSyncedAt).toLocaleString('ja-JP') }}
        </div>
      </SectionCard>
    </div>
  </div>
</template>
