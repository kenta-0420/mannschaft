<script setup lang="ts">
import { watch } from 'vue'
import type { AdminBusinessAlertTeam } from '~/types/admin-business-alert'

const { getSummary } = useAdminBusinessAlertApi()
const { t } = useI18n()

const teams = ref<AdminBusinessAlertTeam[]>([])
const totalPending = ref(0)
const loading = ref(true)

/** 問い合わせチャンネルが設定されているチームが1件以上あるか */
const hasAnyInquiryChannel = computed(() =>
  teams.value.some(t => t.links.inquiryChannelUrl !== null),
)

async function fetchSummary() {
  try {
    const res = await getSummary()
    teams.value = res.data.teams
    totalPending.value = res.data.totalPending
  }
  catch {
    // サイレント - 取得失敗時は前回データをそのまま表示
  }
  finally {
    loading.value = false
  }
}

/**
 * 業務アラートに関連する通知種別一覧。
 * これらの通知を受信した際にサマリーを即時再取得する。
 */
const ALERT_TYPES = [
  'RESERVATION_RECEIVED',
  'RESERVATION_PENDING_APPROVAL',
  'RESERVATION_CANCELLED_BY_MEMBER',
  'INQUIRY_RECEIVED',
]

// WebSocket経由の通知受信時にサマリーを即時更新する（F10.7 WebSocket連動）
const notificationStore = useNotificationStore()

watch(
  () => notificationStore.latestNotification,
  (notification) => {
    if (notification?.notificationType && ALERT_TYPES.includes(notification.notificationType)) {
      fetchSummary()
    }
  },
)

let timer: ReturnType<typeof setInterval>
onMounted(() => {
  fetchSummary()
  timer = setInterval(fetchSummary, 60000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <DashboardWidgetCard
    :title="t('admin.businessAlert.title')"
    icon="pi pi-bell"
    :loading="loading"
    refreshable
    @refresh="fetchSummary"
  >
    <div v-if="totalPending === 0 && !loading && !hasAnyInquiryChannel">
      <DashboardEmptyState icon="pi pi-check-circle" :message="t('admin.businessAlert.noAlerts')" />
    </div>
    <div v-else class="divide-y divide-surface-300 dark:divide-surface-600">
      <div
        v-for="team in teams"
        :key="team.teamId"
        class="py-3"
      >
        <!-- チーム名 -->
        <p class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
          {{ team.teamName }}
        </p>
        <div class="space-y-1">
          <!-- 予約モジュール有効時：新規予約 / 承認待ち -->
          <template v-if="team.reservationModuleEnabled">
            <button
              type="button"
              class="flex w-full items-center justify-between rounded px-2 py-1 text-sm hover:bg-surface-50 dark:hover:bg-surface-700"
              @click="navigateTo(team.links.reservationsUrl)"
            >
              <span class="text-surface-600 dark:text-surface-400">
                {{ t('admin.businessAlert.newReservations') }}
              </span>
              <span class="flex items-center gap-1">
                <Badge
                  :value="team.alerts.newReservations"
                  :severity="team.alerts.newReservations > 0 ? 'danger' : 'secondary'"
                />
                <i class="pi pi-arrow-right text-xs text-surface-400" />
              </span>
            </button>
            <button
              type="button"
              class="flex w-full items-center justify-between rounded px-2 py-1 text-sm hover:bg-surface-50 dark:hover:bg-surface-700"
              @click="navigateTo(team.links.reservationsUrl)"
            >
              <span class="text-surface-600 dark:text-surface-400">
                {{ t('admin.businessAlert.pendingApproval') }}
              </span>
              <span class="flex items-center gap-1">
                <Badge
                  :value="team.alerts.pendingApproval"
                  :severity="team.alerts.pendingApproval > 0 ? 'danger' : 'secondary'"
                />
                <i class="pi pi-arrow-right text-xs text-surface-400" />
              </span>
            </button>
          </template>
          <!-- 問い合わせチャンネルあり時 -->
          <button
            v-if="team.links.inquiryChannelUrl !== null"
            type="button"
            class="flex w-full items-center justify-between rounded px-2 py-1 text-sm hover:bg-surface-50 dark:hover:bg-surface-700"
            @click="navigateTo(team.links.inquiryChannelUrl!)"
          >
            <span class="text-surface-600 dark:text-surface-400">
              {{ t('admin.businessAlert.unreadInquiries') }}
            </span>
            <span class="flex items-center gap-1">
              <Badge
                :value="team.alerts.unreadInquiries"
                :severity="team.alerts.unreadInquiries > 0 ? 'danger' : 'secondary'"
              />
              <i class="pi pi-arrow-right text-xs text-surface-400" />
            </span>
          </button>
        </div>
      </div>
    </div>
  </DashboardWidgetCard>
</template>
