<script setup lang="ts">
/**
 * サイドバー化 Phase1: 薄型ヘッダー（高さ64px = 既存 top-16 互換）。
 *
 * 左端に常設のパネル型トグル（デスクトップ=レール開閉／モバイル=ドロワー開閉を、
 * 既存コードベースの慣行〔hidden md:.../md:hidden の2ボタン切替〕に合わせて実装）。
 * ロゴ長押し600ms・PWAインストール・目安箱はモーダル表示のトリガーのみ担い、
 * 実際のモーダルコンポーネントは default.vue 側の共有インスタンス（分岐の外・両モード共通）
 * を開閉させるため、イベントとして親へ emit する。
 */
const emit = defineEmits<{
  'open-quick-memo': []
  'open-feedback': []
  'open-ios-install': []
}>()

const authStore = useAuthStore()
const inboxStore = useInboxStore()
const appShellStore = useAppShellStore()
const router = useRouter()
const { t } = useI18n()

// PWA インストール
const { canInstall, isInstalled, isIOS, isDismissedThisSession, promptInstall } = usePWAInstall()
const showPwaInstallBtn = computed(
  () => !isInstalled.value && !isDismissedThisSession.value && (canInstall.value || isIOS.value),
)
async function handlePwaInstall() {
  if (isIOS.value) {
    emit('open-ios-install')
  } else {
    await promptInstall()
  }
}

// Mannschaftロゴ長押し(600ms) → ポイっとメモ作成モーダル（既存 default.vue と同一挙動）
const logoLongPressTimer = ref<ReturnType<typeof setTimeout> | null>(null)
const logoLongPressTriggered = ref(false)

function startLogoLongPress() {
  logoLongPressTriggered.value = false
  logoLongPressTimer.value = setTimeout(() => {
    logoLongPressTriggered.value = true
    emit('open-quick-memo')
  }, 600)
}
function cancelLogoLongPress() {
  if (logoLongPressTimer.value) {
    clearTimeout(logoLongPressTimer.value)
    logoLongPressTimer.value = null
  }
}
function handleLogoClick() {
  if (!logoLongPressTriggered.value) {
    router.push('/dashboard')
  }
  logoLongPressTriggered.value = false
}
</script>

<template>
  <header class="sticky top-0 z-50 h-16 border-b border-surface bg-surface-0 shadow-sm dark:border-surface-700 dark:bg-surface-900">
    <div class="flex h-full items-center gap-2 px-4">
      <!-- パネル型トグル（デスクトップ: レール開閉） -->
      <button
        type="button"
        class="hidden h-9 w-9 shrink-0 items-center justify-center rounded-lg text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-900 md:inline-flex dark:text-surface-400 dark:hover:bg-surface-800 dark:hover:text-surface-100"
        :aria-label="appShellStore.isRail ? t('global_nav.toggle.expand') : t('global_nav.toggle.collapse')"
        :title="appShellStore.isRail ? t('global_nav.toggle.expand') : t('global_nav.toggle.collapse')"
        @click="appShellStore.toggleUserCollapsed()"
      >
        <svg viewBox="0 0 24 24" class="h-5 w-5" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <rect
            x="5.2" y="6.2" width="4.4" height="11.6" rx="1"
            :fill="!appShellStore.isRail ? 'currentColor' : 'none'"
            :stroke="!appShellStore.isRail ? 'none' : 'currentColor'"
            :opacity="0.9"
          />
        </svg>
      </button>
      <!-- パネル型トグル（モバイル: ドロワー開閉を兼用） -->
      <button
        type="button"
        class="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-900 md:hidden dark:text-surface-400 dark:hover:bg-surface-800 dark:hover:text-surface-100"
        :aria-label="appShellStore.mobileDrawerOpen ? t('global_nav.toggle.closeDrawer') : t('global_nav.toggle.openDrawer')"
        :title="appShellStore.mobileDrawerOpen ? t('global_nav.toggle.closeDrawer') : t('global_nav.toggle.openDrawer')"
        @click="appShellStore.toggleMobileDrawer()"
      >
        <svg viewBox="0 0 24 24" class="h-5 w-5" stroke="currentColor" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="16" rx="2" />
          <rect
            x="5.2" y="6.2" width="4.4" height="11.6" rx="1"
            :fill="appShellStore.mobileDrawerOpen ? 'currentColor' : 'none'"
            :stroke="appShellStore.mobileDrawerOpen ? 'none' : 'currentColor'"
            :opacity="0.9"
          />
        </svg>
      </button>

      <!-- ロゴ（通常タップ→/dashboard、長押し600ms→ポイっとメモ作成モーダル） -->
      <span
        class="cursor-pointer select-none text-2xl font-bold text-primary"
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

      <div class="min-w-0 flex-1" />

      <!-- 右: ヘッダーアクション -->
      <div class="flex items-center gap-1">
        <ClientOnly>
          <template v-if="authStore.isAuthenticated">
            <!-- F15.3: チーム/組織のドロップダウン（ヘッダー残置が確定仕様） -->
            <ScopeNavDropdown scope-type="TEAM" :label="t('scopeFolder.nav.teams')" />
            <ScopeNavDropdown scope-type="ORGANIZATION" :label="t('scopeFolder.nav.organizations')" />

            <SyncProgressIndicator />
            <!-- 目安箱ボタン -->
            <Button
              v-tooltip.bottom="t('feedback.nav_tooltip')"
              icon="pi pi-box"
              text
              rounded
              severity="secondary"
              @click="emit('open-feedback')"
            />
            <!-- F04.11: 受信箱アイコン -->
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
              @click="authStore.serverLogout()"
            />
          </template>
        </ClientOnly>
        <slot name="header-actions" />
      </div>
    </div>
  </header>
</template>
