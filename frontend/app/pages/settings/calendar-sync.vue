<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const gcalApi = useGoogleCalendarApi()
const { getCalendarOnlyAuthUrl } = useUserSettingsApi()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()
const route = useRoute()
const router = useRouter()

/**
 * BE の GoogleCalendarStatusResponse に対応するインターフェース。
 * フィールド名は BE の JSON キーに合わせること（isConnected, googleAccountEmail, ...）。
 */
interface ConnectionStatus {
  isConnected: boolean
  googleAccountEmail: string | null
  googleCalendarId: string | null
  isActive: boolean
  personalSyncEnabled: boolean
  lastSyncError: { type: string; message: string; occurredAt: string } | null
}

/**
 * BE の PersonalSyncStatusResponse に対応するインターフェース。
 */
interface PersonalSyncStatus {
  connected: boolean
  active: boolean
  personalSyncEnabled: boolean
  googleAccountEmail: string | null
  lastSyncError: { type: string; message: string; occurredAt: string } | null
}

const status = ref<ConnectionStatus | null>(null)
const personalSyncStatus = ref<PersonalSyncStatus | null>(null)
const loading = ref(true)
const syncing = ref(false)

async function load() {
  loading.value = true
  try {
    // 接続状態を取得（getPersonalSync の失敗で接続状態表示が消えないよう分離して実行）
    const statusRes = await gcalApi.getConnectionStatus()
    status.value = statusRes.data as ConnectionStatus

    // 接続済みの場合のみ個人同期設定・チーム/組織一覧を取得
    if (status.value?.isConnected) {
      const [syncRes] = await Promise.all([
        gcalApi.getPersonalSync(),
        teamStore.fetchMyTeams(),
        orgStore.fetchMyOrganizations(),
      ])
      personalSyncStatus.value = syncRes.data as PersonalSyncStatus
    }
  } catch (e) {
    console.error('[calendar-sync load error]', e)
    notification.error('読み込みに失敗しました。コンソールを確認してください。')
  } finally {
    loading.value = false
  }
}

async function connectGoogle() {
  try {
    const res = await getCalendarOnlyAuthUrl()
    window.location.href = res.data.authUrl
  } catch {
    notification.error('接続に失敗しました')
  }
}

function handleQueryParams() {
  if (route.query.connected === 'true') {
    notification.success('Google Calendarと連携しました')
  } else if (route.query.error === 'connect_failed') {
    notification.error('Google Calendar連携に失敗しました')
  }
  if (route.query.connected || route.query.error) {
    router.replace({ query: {} })
  }
}

async function disconnectGoogle() {
  if (!confirm('Google Calendar連携を解除しますか？')) return
  try {
    await gcalApi.disconnect()
    notification.success('連携を解除しました')
    await load()
  } catch {
    notification.error('解除に失敗しました')
  }
}

async function saveSettings() {
  if (!personalSyncStatus.value) return
  try {
    await gcalApi.updatePersonalSync({
      personalSyncEnabled: personalSyncStatus.value.personalSyncEnabled,
    })
    notification.success('同期設定を保存しました')
  } catch {
    notification.error('保存に失敗しました')
  }
}

async function manualSync() {
  syncing.value = true
  try {
    await gcalApi.manualSync()
    notification.success('同期を実行しました')
    await load()
  } catch {
    notification.error('同期に失敗しました')
  } finally {
    syncing.value = false
  }
}

onMounted(async () => {
  handleQueryParams()
  await load()
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="Google Calendar 連携" back-to="/settings" />

    <div v-if="loading" class="space-y-4">
      <Skeleton height="6rem" />
      <Skeleton height="10rem" />
    </div>

    <div v-else class="fade-in space-y-6">
      <!-- 接続状態 -->
      <SectionCard title="接続状態">
        <div v-if="status?.isConnected" class="space-y-3">
          <div class="flex items-center gap-3">
            <div
              class="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
            >
              <i class="pi pi-check text-green-600" />
            </div>
            <div>
              <p class="font-medium text-green-700 dark:text-green-400">接続中</p>
              <p class="text-sm text-surface-500">{{ status.googleAccountEmail }}</p>
            </div>
          </div>
          <p v-if="status.lastSyncError" class="text-xs text-red-500">
            同期エラー: {{ status.lastSyncError.message }}
          </p>
          <div class="flex gap-2">
            <Button
              label="手動同期"
              icon="pi pi-refresh"
              size="small"
              outlined
              :loading="syncing"
              @click="manualSync"
            />
            <Button
              label="連携解除"
              icon="pi pi-times"
              size="small"
              severity="danger"
              outlined
              @click="disconnectGoogle"
            />
          </div>
        </div>
        <div v-else>
          <p class="mb-3 text-sm text-surface-500">
            Googleアカウントと連携して、カレンダーを同期できます
          </p>
          <Button
            label="Googleアカウントに接続"
            icon="pi pi-external-link"
            @click="connectGoogle"
          />
        </div>
      </SectionCard>

      <!-- 同期設定 -->
      <SectionCard
        v-if="status?.isConnected && personalSyncStatus"
        title="同期設定"
      >

        <!-- 個人カレンダー -->
        <div
          class="mb-4 flex items-center justify-between border-b border-surface-100 pb-4 dark:border-surface-600"
        >
          <div>
            <p class="font-medium">個人カレンダー</p>
            <p class="text-xs text-surface-500">個人の予定をGoogleカレンダーに同期</p>
          </div>
          <ToggleSwitch v-model="personalSyncStatus.personalSyncEnabled" />
        </div>

        <!-- チーム -->
        <div v-if="teamStore.myTeams.length > 0" class="mb-4">
          <h3 class="mb-2 text-sm font-medium">チームカレンダー</h3>
          <div class="space-y-2">
            <div
              v-for="team in teamStore.myTeams"
              :key="team.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ team.nickname1 || team.name }}</span>
            </div>
          </div>
        </div>

        <!-- 組織 -->
        <div v-if="orgStore.myOrganizations.length > 0" class="mb-4">
          <h3 class="mb-2 text-sm font-medium">組織カレンダー</h3>
          <div class="space-y-2">
            <div
              v-for="org in orgStore.myOrganizations"
              :key="org.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ org.nickname1 || org.name }}</span>
            </div>
          </div>
        </div>

        <Button label="設定を保存" icon="pi pi-check" @click="saveSettings" />
      </SectionCard>
    </div>
  </div>
</template>
