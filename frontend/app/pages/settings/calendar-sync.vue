<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const gcalApi = useGoogleCalendarApi()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()

interface ConnectionStatus { isConnected: boolean; email: string | null; lastSyncedAt: string | null }
interface SyncSettings { personalSync: boolean; teamSyncIds: number[]; orgSyncIds: number[] }

const status = ref<ConnectionStatus | null>(null)
const syncSettings = ref<SyncSettings | null>(null)
const loading = ref(true)
const syncing = ref(false)

async function load() {
  loading.value = true
  try {
    const [statusRes, settingsRes] = await Promise.all([
      gcalApi.getConnectionStatus(),
      gcalApi.getSyncSettings(),
    ])
    status.value = statusRes.data as ConnectionStatus
    syncSettings.value = settingsRes.data as SyncSettings
    await Promise.all([teamStore.fetchMyTeams(), orgStore.fetchMyOrganizations()])
  }
  catch { /* silent */ }
  finally { loading.value = false }
}

async function connectGoogle() {
  try {
    const res = await gcalApi.connect()
    const { authUrl } = res.data as { authUrl: string }
    window.location.href = authUrl
  }
  catch { notification.error('接続に失敗しました') }
}

async function disconnectGoogle() {
  if (!confirm('Google Calendar連携を解除しますか？')) return
  try {
    await gcalApi.disconnect()
    notification.success('連携を解除しました')
    await load()
  }
  catch { notification.error('解除に失敗しました') }
}

async function saveSettings() {
  if (!syncSettings.value) return
  try {
    await gcalApi.updateSyncSettings(syncSettings.value)
    notification.success('同期設定を保存しました')
  }
  catch { notification.error('保存に失敗しました') }
}

async function manualSync() {
  syncing.value = true
  try {
    await gcalApi.manualSync()
    notification.success('同期を実行しました')
    await load()
  }
  catch { notification.error('同期に失敗しました') }
  finally { syncing.value = false }
}

function toggleTeamSync(teamId: number) {
  if (!syncSettings.value) return
  const idx = syncSettings.value.teamSyncIds.indexOf(teamId)
  if (idx >= 0) syncSettings.value.teamSyncIds.splice(idx, 1)
  else syncSettings.value.teamSyncIds.push(teamId)
}

function toggleOrgSync(orgId: number) {
  if (!syncSettings.value) return
  const idx = syncSettings.value.orgSyncIds.indexOf(orgId)
  if (idx >= 0) syncSettings.value.orgSyncIds.splice(idx, 1)
  else syncSettings.value.orgSyncIds.push(orgId)
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '未同期'
  return new Date(dateStr).toLocaleString('ja-JP')
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <h1 class="mb-6 text-2xl font-bold">Google Calendar 連携</h1>

    <div v-if="loading" class="space-y-4">
      <Skeleton height="6rem" />
      <Skeleton height="10rem" />
    </div>

    <div v-else class="space-y-6">
      <!-- 接続状態 -->
      <div class="rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-800">
        <h2 class="mb-4 text-lg font-semibold">接続状態</h2>
        <div v-if="status?.isConnected" class="space-y-3">
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30">
              <i class="pi pi-check text-green-600" />
            </div>
            <div>
              <p class="font-medium text-green-700 dark:text-green-400">接続中</p>
              <p class="text-sm text-surface-500">{{ status.email }}</p>
            </div>
          </div>
          <p class="text-xs text-surface-400">最終同期: {{ formatDate(status.lastSyncedAt) }}</p>
          <div class="flex gap-2">
            <Button label="手動同期" icon="pi pi-refresh" size="small" outlined :loading="syncing" @click="manualSync" />
            <Button label="連携解除" icon="pi pi-times" size="small" severity="danger" outlined @click="disconnectGoogle" />
          </div>
        </div>
        <div v-else>
          <p class="mb-3 text-sm text-surface-500">Googleアカウントと連携して、カレンダーを同期できます</p>
          <Button label="Googleアカウントに接続" icon="pi pi-external-link" @click="connectGoogle" />
        </div>
      </div>

      <!-- 同期設定 -->
      <div v-if="status?.isConnected && syncSettings" class="rounded-xl border border-surface-200 bg-surface-0 p-6 dark:border-surface-700 dark:bg-surface-800">
        <h2 class="mb-4 text-lg font-semibold">同期設定</h2>

        <!-- 個人カレンダー -->
        <div class="mb-4 flex items-center justify-between border-b border-surface-100 pb-4 dark:border-surface-700">
          <div>
            <p class="font-medium">個人カレンダー</p>
            <p class="text-xs text-surface-500">個人の予定をGoogleカレンダーに同期</p>
          </div>
          <ToggleSwitch v-model="syncSettings.personalSync" />
        </div>

        <!-- チーム -->
        <div v-if="teamStore.myTeams.length > 0" class="mb-4">
          <h3 class="mb-2 text-sm font-medium">チームカレンダー</h3>
          <div class="space-y-2">
            <div v-for="team in teamStore.myTeams" :key="team.id" class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50">
              <span class="text-sm">{{ team.nickname1 || team.name }}</span>
              <ToggleSwitch :model-value="syncSettings.teamSyncIds.includes(team.id)" @update:model-value="toggleTeamSync(team.id)" />
            </div>
          </div>
        </div>

        <!-- 組織 -->
        <div v-if="orgStore.myOrganizations.length > 0" class="mb-4">
          <h3 class="mb-2 text-sm font-medium">組織カレンダー</h3>
          <div class="space-y-2">
            <div v-for="org in orgStore.myOrganizations" :key="org.id" class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50">
              <span class="text-sm">{{ org.nickname1 || org.name }}</span>
              <ToggleSwitch :model-value="syncSettings.orgSyncIds.includes(org.id)" @update:model-value="toggleOrgSync(org.id)" />
            </div>
          </div>
        </div>

        <Button label="設定を保存" icon="pi pi-check" @click="saveSettings" />
      </div>
    </div>
  </div>
</template>
