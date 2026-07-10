<script setup lang="ts">
import type { components } from '~/types/generated'

definePageMeta({ middleware: 'auth' })

type MyTeam = { id: number; name: string; nickname1: string | null }
type MyOrganization = { id: number; name: string; nickname1: string | null }
type CalendarSyncToggleResponse = components['schemas']['CalendarSyncToggleResponse']

const gcalApi = useGoogleCalendarApi()
const { getCalendarOnlyAuthUrl } = useUserSettingsApi()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()

/**
 * BE の GoogleCalendarStatusResponse に対応するインターフェース。
 * Lombok @Getter が boolean isConnected を connected() として出力するため、
 * Jackson シリアライズ結果は "connected"/"active" になる。
 */
interface ConnectionStatus {
  connected: boolean
  googleAccountEmail: string | null
  googleCalendarId: string | null
  active: boolean
  personalSyncEnabled: boolean
  lastSyncError: { type: string; message: string; occurredAt: string } | null
}

const status = ref<ConnectionStatus | null>(null)
const loading = ref(true)
const syncing = ref(false)
const showGuide = ref(false)

// 個人カレンダー同期トグル（楽観更新用のローカル状態）
const personalSyncEnabled = ref(false)
const personalSubmitting = ref(false)

// チーム/組織別の同期ON/OFF状態・送信中フラグ（team/org の id をキーにした Record）
const teamSyncEnabled = reactive<Record<number, boolean>>({})
const orgSyncEnabled = reactive<Record<number, boolean>>({})
const teamSubmitting = reactive<Record<number, boolean>>({})
const orgSubmitting = reactive<Record<number, boolean>>({})

async function load() {
  loading.value = true
  try {
    // 接続状態を取得（getSyncSettings の失敗で接続状態表示が消えないよう分離して実行）
    const statusRes = await gcalApi.getConnectionStatus()
    status.value = statusRes.data as ConnectionStatus

    // 接続済みの場合のみ同期設定・チーム/組織一覧を取得
    if (status.value?.connected) {
      const [settingsRes] = await Promise.all([
        gcalApi.getSyncSettings(),
        teamStore.fetchMyTeams(),
        orgStore.fetchMyOrganizations(),
      ])
      const settings = settingsRes.data
      personalSyncEnabled.value = settings?.personalSyncEnabled ?? false

      const byScope = new Map<string, boolean>()
      for (const item of settings?.syncSettings ?? []) {
        if (item.scopeType && item.scopeId !== undefined) {
          byScope.set(`${item.scopeType}:${item.scopeId}`, item.isEnabled ?? false)
        }
      }
      for (const team of teamStore.myTeams) {
        teamSyncEnabled[team.id] = byScope.get(`TEAM:${team.id}`) ?? false
      }
      for (const org of orgStore.myOrganizations) {
        orgSyncEnabled[org.id] = byScope.get(`ORGANIZATION:${org.id}`) ?? false
      }
    }
  } catch (e) {
    console.error('[calendar-sync load error]', e)
    notification.error(t('settings.gcal.toast.load_error'))
  } finally {
    loading.value = false
  }
}

async function connectGoogle() {
  try {
    const res = await getCalendarOnlyAuthUrl()
    window.location.href = res.data.authUrl
  } catch {
    notification.error(t('settings.gcal.toast.connect_error'))
  }
}

function handleQueryParams() {
  if (route.query.connected === 'true') {
    notification.success(t('settings.gcal.toast.oauth_connected'))
  } else if (route.query.error === 'connect_failed') {
    notification.error(t('settings.gcal.toast.oauth_connect_failed'))
  }
  if (route.query.connected || route.query.error) {
    router.replace({ query: {} })
  }
}

async function disconnectGoogle() {
  if (!confirm(t('settings.gcal.disconnect_confirm'))) return
  try {
    await gcalApi.disconnect()
    notification.success(t('settings.gcal.toast.disconnect_success'))
    await load()
  } catch {
    notification.error(t('settings.gcal.toast.disconnect_error'))
  }
}

async function manualSync() {
  syncing.value = true
  try {
    await gcalApi.manualSync()
    notification.success(t('settings.gcal.toast.sync_success'))
    await load()
  } catch {
    notification.error(t('settings.gcal.toast.sync_error'))
  } finally {
    syncing.value = false
  }
}

/**
 * バックフィル対象件数があれば追加で情報トーストを出す。
 */
function notifyBackfill(res: { data?: CalendarSyncToggleResponse } | { data?: { backfillCount?: number } }) {
  const backfillCount = res.data?.backfillCount ?? 0
  if (backfillCount > 0) {
    notification.info(t('settings.gcal.toast.backfill_info', { count: backfillCount }))
  }
}

// 個人カレンダー同期のトグル（FriendVisibilityToggle と同じ楽観更新パターン）
async function onTogglePersonal(next: boolean) {
  const previous = !next
  personalSubmitting.value = true
  try {
    const res = await gcalApi.updatePersonalSync({ isEnabled: next })
    const name = t('settings.gcal.personal_calendar_label')
    notification.success(
      next
        ? t('settings.gcal.toast.toggle_on_success', { name })
        : t('settings.gcal.toast.toggle_off_success', { name }),
    )
    if (next) notifyBackfill(res)
  } catch (e) {
    personalSyncEnabled.value = previous
    handleApiError(e)
  } finally {
    personalSubmitting.value = false
  }
}

async function onToggleTeam(team: MyTeam, next: boolean) {
  const previous = !next
  teamSubmitting[team.id] = true
  try {
    const res = await gcalApi.toggleTeamSync(String(team.id), { isEnabled: next })
    const name = team.nickname1 || team.name
    notification.success(
      next
        ? t('settings.gcal.toast.toggle_on_success', { name })
        : t('settings.gcal.toast.toggle_off_success', { name }),
    )
    if (next) notifyBackfill(res)
  } catch (e) {
    teamSyncEnabled[team.id] = previous
    handleApiError(e)
  } finally {
    teamSubmitting[team.id] = false
  }
}

async function onToggleOrg(org: MyOrganization, next: boolean) {
  const previous = !next
  orgSubmitting[org.id] = true
  try {
    const res = await gcalApi.toggleOrgSync(String(org.id), { isEnabled: next })
    const name = org.nickname1 || org.name
    notification.success(
      next
        ? t('settings.gcal.toast.toggle_on_success', { name })
        : t('settings.gcal.toast.toggle_off_success', { name }),
    )
    if (next) notifyBackfill(res)
  } catch (e) {
    orgSyncEnabled[org.id] = previous
    handleApiError(e)
  } finally {
    orgSubmitting[org.id] = false
  }
}

onMounted(async () => {
  handleQueryParams()
  await load()
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('settings.gcal.section_title')" help @help="showGuide = true" />

    <CalendarSyncHelpDialog v-model:visible="showGuide" />

    <div v-if="loading" class="space-y-4">
      <Skeleton height="6rem" />
      <Skeleton height="10rem" />
    </div>

    <div v-else class="fade-in space-y-6">
      <!-- 接続状態 -->
      <SectionCard :title="t('settings.gcal.connection_status_title')">
        <div v-if="status?.connected" class="space-y-3">
          <div class="flex items-center gap-3">
            <div
              class="flex h-10 w-10 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30"
            >
              <i class="pi pi-check text-green-600" />
            </div>
            <div>
              <p class="font-medium text-green-700 dark:text-green-400">
                {{ t('settings.gcal.connected_label') }}
              </p>
              <p class="text-sm text-surface-500">{{ status.googleAccountEmail }}</p>
            </div>
          </div>
          <p v-if="status.lastSyncError" class="text-xs text-red-500">
            {{ t('settings.gcal.last_sync_error', { message: status.lastSyncError.message }) }}
          </p>
          <div class="flex gap-2">
            <Button
              :label="t('settings.gcal.manual_sync_button')"
              icon="pi pi-refresh"
              size="small"
              outlined
              :loading="syncing"
              @click="manualSync"
            />
            <Button
              :label="t('settings.gcal.disconnect_button')"
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
            {{ t('settings.gcal.not_connected_description') }}
          </p>
          <Button
            :label="t('settings.gcal.connect_button')"
            icon="pi pi-external-link"
            @click="connectGoogle"
          />
        </div>
      </SectionCard>

      <!-- 同期設定（AC-13: 未接続時は表示しない） -->
      <SectionCard v-if="status?.connected" :title="t('settings.gcal.sync_settings_title')">
        <!-- 個人カレンダー -->
        <div
          class="mb-4 flex items-center justify-between border-b border-surface-100 pb-4 dark:border-surface-600"
        >
          <div>
            <p class="font-medium">{{ t('settings.gcal.personal_calendar_label') }}</p>
            <p class="text-xs text-surface-500">{{ t('settings.gcal.personal_calendar_description') }}</p>
          </div>
          <ToggleSwitch
            v-model="personalSyncEnabled"
            :disabled="personalSubmitting"
            data-testid="gcal-personal-toggle"
            @update:model-value="onTogglePersonal"
          />
        </div>

        <!-- チーム -->
        <div class="mb-4">
          <h3 class="mb-2 text-sm font-medium">{{ t('settings.gcal.team_calendars_label') }}</h3>
          <div v-if="teamStore.myTeams.length > 0" class="space-y-2">
            <div
              v-for="team in teamStore.myTeams"
              :key="team.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ team.nickname1 || team.name }}</span>
              <ToggleSwitch
                v-model="teamSyncEnabled[team.id]"
                :disabled="teamSubmitting[team.id]"
                :data-testid="`gcal-team-toggle-${team.id}`"
                @update:model-value="(next: boolean) => onToggleTeam(team, next)"
              />
            </div>
          </div>
          <p v-else class="text-sm text-surface-500">{{ t('settings.gcal.no_team_calendars') }}</p>
        </div>

        <!-- 組織 -->
        <div>
          <h3 class="mb-2 text-sm font-medium">{{ t('settings.gcal.org_calendars_label') }}</h3>
          <div v-if="orgStore.myOrganizations.length > 0" class="space-y-2">
            <div
              v-for="org in orgStore.myOrganizations"
              :key="org.id"
              class="flex items-center justify-between rounded-lg bg-surface-50 px-3 py-2 dark:bg-surface-700/50"
            >
              <span class="text-sm">{{ org.nickname1 || org.name }}</span>
              <ToggleSwitch
                v-model="orgSyncEnabled[org.id]"
                :disabled="orgSubmitting[org.id]"
                :data-testid="`gcal-org-toggle-${org.id}`"
                @update:model-value="(next: boolean) => onToggleOrg(org, next)"
              />
            </div>
          </div>
          <p v-else class="text-sm text-surface-500">{{ t('settings.gcal.no_org_calendars') }}</p>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
