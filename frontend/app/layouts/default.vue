<script setup lang="ts">
const authStore = useAuthStore()
const syncStore = useSyncStore()
const teamStore = useTeamStore()
const route = useRoute()
const router = useRouter()
const { t } = useI18n()

// PWA インストール
const { canInstall, isInstalled, isIOS, isDismissedThisSession, promptInstall } = usePWAInstall()
const iosInstallModalVisible = ref(false)
const showPwaInstallBtn = computed(
  () => !isInstalled.value && !isDismissedThisSession.value && (canInstall.value || isIOS.value),
)
async function handlePwaInstall() {
  if (isIOS.value) {
    iosInstallModalVisible.value = true
  } else {
    await promptInstall()
  }
}

// Mannschaftロゴ長押し → ポイっとメモ作成モーダル（600ms）
// 別ページ遷移にすると意識が遷移先に持っていかれ、元の作業を忘れてしまうため
// 簡易メモとしての価値を保つためにモーダルで開く（ADHD 配慮）
const logoLongPressTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const logoLongPressTriggered = ref(false)
const quickMemoModalVisible = ref(false)

const startLogoLongPress = () => {
  logoLongPressTriggered.value = false
  logoLongPressTimer.value = setTimeout(() => {
    logoLongPressTriggered.value = true
    quickMemoModalVisible.value = true
  }, 600)
}

const cancelLogoLongPress = () => {
  if (logoLongPressTimer.value) {
    clearTimeout(logoLongPressTimer.value)
    logoLongPressTimer.value = null
  }
}

const handleLogoClick = () => {
  if (!logoLongPressTriggered.value) {
    router.push('/dashboard')
  }
  logoLongPressTriggered.value = false
}

const inboxStore = useInboxStore()

const isMounted = ref(false)
const showMobileMenu = ref(false)
const feedbackModalVisible = ref(false)

let inboxPollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  isMounted.value = true
  // fetchSummary はストア内部で _handleError 済み（バッジ件数取得）。
  // 60 秒ごとのポーリングで毎回トーストを出さないよう、ここでは再 throw のみ握りつぶす。
  inboxStore.fetchSummary().catch(() => {})
  inboxPollTimer = setInterval(() => {
    inboxStore.fetchSummary().catch(() => {})
  }, 60_000)
})

onUnmounted(() => {
  if (inboxPollTimer) clearInterval(inboxPollTimer)
})

watch(
  () => route.path,
  () => {
    showMobileMenu.value = false
  },
)

/**
 * F20.1: ナビゲーション設定ストアから動的に表示項目を取得する。
 * ハードコード navItems / mobileNavItems を廃止し、store の visibleFeatures /
 * visibleMobileFeatures に一元化。
 */
const navSettingsStore = useNavSettingsStore()

/** 未解決コンフリクトがある場合のみ「同期」ナビを表示 */
const showSyncNav = computed(() => syncStore.hasConflicts)

/** NEIGHBORHOOD/CONDO テンプレートかつ DEPUTY_ADMIN 以上のチームが 1 つでもある場合に代理入力デスクを表示 */
const showProxyDeskNav = computed(() =>
  teamStore.myTeams.some(
    (team) =>
      (team.template === 'NEIGHBORHOOD' || team.template === 'CONDO') &&
      (team.role === 'ADMIN' || team.role === 'SYSTEM_ADMIN' || team.role === 'DEPUTY_ADMIN'),
  ),
)

const systemAdminItem = { label: 'SYSTEM', icon: 'pi pi-shield', to: '/system-admin' }

const proxyDeskItem = { label: t('proxy.title'), icon: 'pi pi-tablet', to: '/admin/proxy-desk' }

// 全ナビアイテムのパス一覧（現在ルートが完全一致している場合に prefix match を無効化するため）
const allNavPaths = computed(() => [
  '/dashboard',
  ...navSettingsStore.visibleFeatures.map((f) => f.path),
  ...(showProxyDeskNav.value ? [proxyDeskItem.to] : []),
])

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

function isActive(path: string, exact = false): boolean {
  if (exact) return route.path === path
  if (route.path === path) return true
  // 現在ルートがいずれかのナビアイテムと完全一致する場合、prefix match は採用しない
  // （例: /my/shift が /my/shift と完全一致 → /my の startsWith 判定を無効化）
  if (allNavPaths.value.some((p) => p === route.path)) return false
  return route.path.startsWith(path + '/')
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
    <!-- ヘッダー -->
    <header class="sticky top-0 z-50 bg-surface-0 border-b border-surface shadow-sm dark:bg-surface-900 dark:border-surface-700">
      <div class="mx-auto flex h-16 max-w-screen-2xl items-center justify-between px-4">
        <!-- 左: ロゴ + ナビゲーション -->
        <div class="flex min-w-0 flex-1 items-center gap-6">
          <!-- 通常タップ→/dashboard、長押し600ms→ポイっとメモモーダル（ADHD向け裏仕掛け） -->
          <span
            class="text-3xl font-bold text-primary cursor-pointer select-none"
            style="touch-action: manipulation"
            role="link"
            tabindex="0"
            @mousedown="startLogoLongPress"
            @mouseup="cancelLogoLongPress"
            @mouseleave="cancelLogoLongPress"
            @touchstart="startLogoLongPress"
            @touchend="cancelLogoLongPress"
            @touchcancel="cancelLogoLongPress"
            @click="handleLogoClick"
            @keydown.enter="handleLogoClick"
          >
            Mannschaft
          </span>
          <!-- overflow-x-auto が正しく発動するよう、HTML div で min-w-0 flex-1 を確保してから nav を内包する -->
          <div class="hidden md:flex min-w-0 flex-1">
          <ClientOnly>
            <!--
              横スクロール: ネイティブの横スワイプ(deltaX)で横スクロールさせる。
              overscroll-x-contain で、スクロール端での横スワイプがブラウザの
              「戻る/進む」ジェスチャーに横取りされるのを防ぐ。
            -->
            <nav
              v-if="authStore.isAuthenticated"
              class="flex min-w-0 w-full items-center gap-1 overflow-x-auto overscroll-x-contain scrollbar-thin-nav"
            >
              <!-- ダッシュボード（最初に固定表示） -->
              <NuxtLink
                to="/dashboard"
                class="flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
                :class="isActive('/dashboard') ? 'bg-primary/10 text-primary' : 'text-surface-600 dark:text-surface-400'"
              >
                <i class="pi pi-home" />
                ダッシュボード
              </NuxtLink>
              <!-- F15.3: チーム/組織のドロップダウン（マイフォルダ統合 UX） -->
              <ScopeNavDropdown
                scope-type="TEAM"
                :label="t('scopeFolder.nav.teams')"
              />
              <ScopeNavDropdown
                scope-type="ORGANIZATION"
                :label="t('scopeFolder.nav.organizations')"
              />
              <!-- F20.1: ナビ設定ストアから動的生成（村・ブログ等を含む） -->
              <NuxtLink
                v-for="item in navSettingsStore.visibleFeatures"
                :key="item.key"
                :to="item.path"
                class="flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
                :class="isActive(item.path) ? 'bg-primary/10 text-primary' : 'text-surface-600 dark:text-surface-400'"
              >
                <i :class="item.icon" />
                {{ $t(item.labelKey, item.labelKey) }}
              </NuxtLink>
              <!-- 代理入力デスク（DEPUTY_ADMIN 以上のみ表示） -->
              <NuxtLink
                v-if="showProxyDeskNav"
                :to="proxyDeskItem.to"
                class="flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
                :class="isActive(proxyDeskItem.to) ? 'bg-primary/10 text-primary' : 'text-surface-600 dark:text-surface-400'"
              >
                <i :class="proxyDeskItem.icon" />
                {{ proxyDeskItem.label }}
              </NuxtLink>
              <NuxtLink
                v-if="authStore.isSystemAdmin"
                :to="systemAdminItem.to"
                class="flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-red-50 dark:hover:bg-red-900/30"
                :class="isActive(systemAdminItem.to) ? 'bg-red-100 text-red-600 dark:bg-red-900/40 dark:text-red-400' : 'text-red-500 dark:text-red-400'"
              >
                <i :class="systemAdminItem.icon" />
                {{ systemAdminItem.label }}
              </NuxtLink>
              <!-- 同期（コンフリクトがある場合のみ表示） -->
              <NuxtLink
                v-if="showSyncNav"
                to="/sync/conflicts"
                class="relative flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
                :class="isActive('/sync') ? 'bg-primary/10 text-primary' : 'text-surface-600 dark:text-surface-400'"
              >
                <i class="pi pi-sync" />
                {{ t('sync.nav_label') }}
                <span
                  v-if="syncStore.conflictCount > 0"
                  class="absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white"
                >
                  {{ syncStore.conflictCount }}
                </span>
              </NuxtLink>
            </nav>
          </ClientOnly>
          </div>
        </div>

        <!-- 右: ユーザーメニュー -->
        <div class="flex items-center gap-3">
          <ClientOnly>
            <template v-if="authStore.isAuthenticated">
              <SyncProgressIndicator />
              <!-- 目安箱ボタン -->
              <Button
                v-if="authStore.isAuthenticated"
                v-tooltip.bottom="t('feedback.nav_tooltip')"
                icon="pi pi-box"
                text
                rounded
                severity="secondary"
                @click="feedbackModalVisible = true"
              />
              <!-- F04.11: 受信箱アイコン（ナビバー常時表示） -->
              <NuxtLink
                to="/inbox"
                class="relative flex shrink-0 items-center justify-center rounded-lg p-2 transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
                :aria-label="t('inbox.title')"
                :title="t('inbox.title')"
              >
                <i class="pi pi-inbox text-surface-600 dark:text-surface-300" />
                <Badge
                  v-if="inboxStore.inboxCount > 0"
                  :value="inboxStore.inboxCount > 99 ? '99+' : inboxStore.inboxCount"
                  severity="danger"
                  class="absolute -right-1 -top-1 shadow-md ring-2 ring-white dark:ring-surface-900 !min-w-[1.1rem] !h-[1.1rem] !text-[0.6rem]"
                />
              </NuxtLink>
              <NotificationBell />
              <!-- PWAインストールボタン（未インストール時のみ） -->
              <Button
                v-if="showPwaInstallBtn"
                v-tooltip.bottom="'アプリをインストール'"
                icon="pi pi-download"
                text
                rounded
                severity="secondary"
                @click="handlePwaInstall"
              />
              <Button
                v-tooltip.bottom="'ログアウト'"
                icon="pi pi-sign-out"
                text
                rounded
                severity="secondary"
                class="hidden md:inline-flex"
                @click="authStore.serverLogout()"
              />
              <!-- ハンバーガーボタン (モバイルのみ) -->
              <Button
                icon="pi pi-bars"
                text
                rounded
                severity="secondary"
                class="md:hidden"
                @click="showMobileMenu = true"
              />
            </template>
          </ClientOnly>
          <slot name="header-actions" />
        </div>
      </div>
    </header>

    <!-- PWA: オフラインバナー -->
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

    <!-- メインコンテンツ -->
    <main class="mx-auto max-w-screen-2xl p-4">
      <slot />
    </main>

    <ClientOnly>
      <ErrorReportDialog />
      <IosInstallGuideModal v-model:visible="iosInstallModalVisible" />
      <QuickMemoCaptureModal v-model:visible="quickMemoModalVisible" />
      <FeedbackSubmitModal v-model:visible="feedbackModalVisible" />
      <!-- F10.1: 管理者変身中バナー -->
      <AdminImpersonationBanner />

      <!-- モバイルメニュー Drawer -->
      <Drawer v-model:visible="showMobileMenu" position="left" class="w-72">
        <template #header>
          <span class="text-xl font-bold text-primary">Mannschaft</span>
        </template>
        <nav class="flex flex-col gap-1 pt-2">
          <!-- F20.1: ナビ設定ストアから動的生成 -->
          <NuxtLink
            v-for="item in navSettingsStore.visibleMobileFeatures"
            :key="item.key"
            :to="item.path"
            class="flex items-center gap-3 rounded-lg px-4 py-3 text-sm font-medium transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
            :class="isActive(item.path) ? 'bg-primary/10 text-primary' : 'text-surface-700 dark:text-surface-200'"
            @click="showMobileMenu = false"
          >
            <i :class="[item.icon, 'text-base']" />
            {{ $t(item.labelKey, item.labelKey) }}
          </NuxtLink>
          <!-- 同期（コンフリクトがある場合のみ） -->
          <NuxtLink
            v-if="showSyncNav"
            to="/sync/conflicts"
            class="relative flex items-center gap-3 rounded-lg px-4 py-3 text-sm font-medium transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
            :class="isActive('/sync') ? 'bg-primary/10 text-primary' : 'text-surface-700 dark:text-surface-200'"
          >
            <i class="pi pi-sync text-base" />
            {{ t('sync.nav_label') }}
            <span
              v-if="syncStore.conflictCount > 0"
              class="ml-auto flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white"
            >
              {{ syncStore.conflictCount }}
            </span>
          </NuxtLink>
          <!-- 代理入力デスク（DEPUTY_ADMIN 以上のみ表示） -->
          <NuxtLink
            v-if="showProxyDeskNav"
            :to="proxyDeskItem.to"
            class="flex items-center gap-3 rounded-lg px-4 py-3 text-sm font-medium transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
            :class="isActive(proxyDeskItem.to) ? 'bg-primary/10 text-primary' : 'text-surface-700 dark:text-surface-200'"
            @click="showMobileMenu = false"
          >
            <i :class="[proxyDeskItem.icon, 'text-base']" />
            {{ proxyDeskItem.label }}
          </NuxtLink>
          <!-- システム管理 -->
          <NuxtLink
            v-if="authStore.isSystemAdmin"
            :to="systemAdminItem.to"
            class="flex items-center gap-3 rounded-lg px-4 py-3 text-sm font-medium transition-colors hover:bg-red-50 dark:hover:bg-red-900/30"
            :class="isActive(systemAdminItem.to) ? 'bg-red-100 text-red-600 dark:bg-red-900/40 dark:text-red-400' : 'text-red-500 dark:text-red-400'"
          >
            <i :class="[systemAdminItem.icon, 'text-base']" />
            {{ systemAdminItem.label }}
          </NuxtLink>
        </nav>
        <div class="mt-4 border-t border-surface-200 pt-4 dark:border-surface-700">
          <Button
            v-if="authStore.isAuthenticated"
            :label="t('feedback.nav_button')"
            icon="pi pi-box"
            text
            severity="secondary"
            class="w-full justify-start"
            @click="() => { feedbackModalVisible = true; showMobileMenu = false }"
          />
          <Button
            label="ログアウト"
            icon="pi pi-sign-out"
            text
            severity="secondary"
            class="w-full justify-start"
            @click="authStore.serverLogout()"
          />
        </div>
      </Drawer>
    </ClientOnly>
  </div>
</template>
