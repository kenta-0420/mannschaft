<script setup lang="ts">
// サイドバー化 Phase3: 旧上部ナビ・app-shell-enabled フラグを撤去し、常に AppShell
// （AppHeader/GlobalSidebar）を描画する。ロゴ長押し・PWAインストールボタン・ナビ項目の
// グルーピングは AppHeader.vue / useAppNavGroups.ts へ移設済みのため、このファイルでは扱わない。
const authStore = useAuthStore()
const { t } = useI18n()

// AppShell からの emit で開閉する共有モーダル状態（分岐なく単一インスタンスとして描画するため
// このレイアウトに残す）。
const iosInstallModalVisible = ref(false)
const quickMemoModalVisible = ref(false)
const feedbackModalVisible = ref(false)

const inboxStore = useInboxStore()

const isMounted = ref(false)

let inboxPollTimer: ReturnType<typeof setInterval> | null = null

// F10.7 WebSocket 通知連動（隊5・AC-9前段）: 認証済みセッションの間だけグローバルに1本、
// /user/queue/notifications を購読する（BE の Principal 配線完了後に個別通知が実配信される）。
const userNotificationSocket = useUserNotificationSocket()

onMounted(() => {
  isMounted.value = true
  // fetchSummary はストア内部で _handleError 済み（バッジ件数取得）。
  // 60 秒ごとのポーリングで毎回トーストを出さないよう、ここでは再 throw のみ握りつぶす。
  // eslint-disable-next-line no-restricted-syntax -- 受信箱バッジ取得。エラーはストア側で _handleError 済み。ここでの再throwのみ握りつぶすのが正しい
  inboxStore.fetchSummary().catch(() => {})
  inboxPollTimer = setInterval(() => {
    // eslint-disable-next-line no-restricted-syntax -- 60秒ポーリング。ストア側で _handleError 済み・毎回トーストを出さないため握りつぶすのが正しい
    inboxStore.fetchSummary().catch(() => {})
  }, 60_000)

  if (authStore.isAuthenticated) {
    userNotificationSocket.start()
  }
})

onUnmounted(() => {
  if (inboxPollTimer) clearInterval(inboxPollTimer)
  userNotificationSocket.stop()
})

// ログイン/ログアウトでの認証状態遷移に追随して購読を開始/停止する
// （レイアウト自体は unmount せずに済むセッション途中の状態変化にも対応）。
watch(
  () => authStore.isAuthenticated,
  (authenticated) => {
    if (authenticated) {
      userNotificationSocket.start()
    } else {
      userNotificationSocket.stop()
    }
  },
)

const guardianshipSwitchStore = useGuardianshipSwitchStore()
const { endSwitch: apiEndSwitch } = useGuardianshipApi()
const notification = useNotification()

async function handleEndSwitch() {
  if (!guardianshipSwitchStore.activeChild) return
  try {
    await apiEndSwitch(guardianshipSwitchStore.activeChild.childUserId)
    guardianshipSwitchStore.endSwitch()
    notification.success(t('proxy.guardianship.switch.endSuccess'))
  } catch {
    // エラーは useApi の共通ハンドラに任せる
  }
}
</script>

<template>
  <!-- マウント前（SSR含む）はスピナーのみ表示してフラッシュを防ぐ -->
  <div
    v-if="!isMounted"
    class="flex min-h-screen items-center justify-center dark:bg-surface-ground"
    style="background-color: var(--bg-color, #f3efe0)"
  >
    <LoadingBounce />
  </div>

  <div v-else class="min-h-screen dark:bg-surface-ground" style="background-color: var(--bg-color, #f3efe0)">
    <AppShell
      @open-quick-memo="quickMemoModalVisible = true"
      @open-feedback="feedbackModalVisible = true"
      @open-ios-install="iosInstallModalVisible = true"
    >
      <template #header-actions>
        <slot name="header-actions" />
      </template>
      <template #banners>
        <ClientOnly>
          <OfflineStatusBanner />
          <!-- 後見切替中バナー -->
          <div
            v-if="guardianshipSwitchStore.isActingAs"
            class="bg-orange-100 border-b border-orange-300 text-orange-800 text-sm py-1 px-4 flex items-center justify-between dark:bg-orange-900/30 dark:border-orange-700 dark:text-orange-300"
          >
            <span>{{ $t('proxy.guardianship.switch.actingAs', { name: guardianshipSwitchStore.activeChild?.displayName ?? '' }) }}</span>
            <button class="text-xs underline hover:no-underline" @click="handleEndSwitch">
              {{ $t('proxy.guardianship.switch.end') }}
            </button>
          </div>
        </ClientOnly>
      </template>
      <slot />
    </AppShell>

    <!-- モーダル群・バナー群は AppShell の外（単一インスタンス） -->
    <ClientOnly>
      <ErrorReportDialog />
      <IosInstallGuideModal v-model:visible="iosInstallModalVisible" />
      <QuickMemoCaptureModal v-model:visible="quickMemoModalVisible" />
      <FeedbackSubmitModal v-model:visible="feedbackModalVisible" />
      <!-- F10.1: 管理者変身中バナー -->
      <AdminImpersonationBanner />
    </ClientOnly>
  </div>
</template>
