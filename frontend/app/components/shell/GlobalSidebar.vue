<script setup lang="ts">
/**
 * サイドバー化 Phase1: グローバルサイドバー（A案・Slack型の1本目）。
 *
 * ルート要素は必ず <nav data-testid="global-sidebar"> のみとし、<aside> で包まない。
 * 既存 E2E（frontend/tests/e2e/organizations/org-sidebar.spec.ts 等）が
 * `aside nav` セレクタでスコープサイドバー（TeamSidebar/OrganizationSidebar）を
 * 一意に特定しており、ここを <aside> で包むと2要素マッチして既存 spec が壊れる。
 */
const props = defineProps<{
  /** true の場合、レール状態に関わらず常にワイド表示にする（モバイルDrawer内での強制表示用） */
  forceWide?: boolean
}>()

/**
 * AC-16: モバイルドロワー（forceWide=true）内でのみ、ヘッダーから退避した
 * アクション（チーム/組織切替・目安箱・受信箱・PWAインストール・ログアウト・Sync表示）を
 * 描画する。開閉イベントはヘッダーと同じく親（AppShell）へ中継する。
 */
const emit = defineEmits<{
  'open-feedback': []
  'open-ios-install': []
}>()

const appShellStore = useAppShellStore()
const { groups, allPaths } = useAppNavGroups()
const route = useRoute()
const authStore = useAuthStore()
const inboxStore = useInboxStore()
const { t } = useI18n()

const rail = computed(() => !props.forceWide && appShellStore.isRail)

// PWA インストール（AppHeader.vue のデスクトップ版と同一ロジック。モバイルドロワー内退避用）
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

// Phase2 AC-14: スコープページ（チーム/組織）の自動レール収縮中のみレール最下部に表示する
// 説明チップ（sidebar-prototype.html の .gnav-bottom / .auto-chip 相当）。
// 一時展開中（scopeExpanded=true）は isRail が false になるため rail 判定と自然に連動する。
const showAutoCollapseChip = computed(() => rail.value && appShellStore.forceRail)

// default.vue の isActive(path) と同一ロジック（exact 引数は既存呼び出し側で未使用のため簡略化）。
// 現在ルートがいずれかのナビパスと完全一致する場合、prefix match は採用しない。
function isItemActive(path: string): boolean {
  if (route.path === path) return true
  if (allPaths.value.some(p => p === route.path)) return false
  return route.path.startsWith(`${path}/`)
}
</script>

<template>
  <nav
    aria-label="グローバルナビゲーション"
    data-testid="global-sidebar"
    class="flex flex-shrink-0 flex-col overflow-visible border-r border-surface-200 bg-surface-0 transition-[width] duration-300 ease-[cubic-bezier(0.4,0,0.2,1)] dark:border-surface-700 dark:bg-surface-900 motion-reduce:transition-none"
    :class="[
      rail ? 'w-[68px]' : 'w-[260px]',
      /* Phase4: 独立スクロール。デスクトップ（forceWide=false）ではヘッダー直下に sticky 固定し、
         高さをビューポートに束ねる。ページ本文のスクロールとは独立に、ナビ項目リスト（下の div）
         だけが内部 overflow-y でスクロールする（ドキュメントサイトの左ナビと同じ挙動）。
         モバイル Drawer 内（forceWide=true）では Drawer が高さを持つため h-full で全高に合わせる。 */
      forceWide
        ? 'h-full'
        : 'sticky top-[var(--app-header-h)] h-[calc(100vh-var(--app-header-h))] self-start',
    ]"
  >
    <div
      class="scrollbar-thin-sidebar flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto overflow-x-visible px-2 py-2.5"
    >
      <template v-for="group in groups" :key="group.key">
        <!-- グループ見出し（レール時は罫線のみ・ラベル非表示） -->
        <div
          v-if="rail"
          class="mx-1 mt-1.5 border-t border-surface-200 pt-1.5 first:mt-0 first:border-t-0 first:pt-0 dark:border-surface-700"
        />
        <div
          v-else
          class="px-2.5 pb-1 pt-3 text-[10.5px] font-bold uppercase tracking-wider text-surface-400 first:pt-1 dark:text-surface-500"
        >
          {{ $t(group.labelKey) }}
        </div>

        <GlobalNavItem
          v-for="item in group.items"
          :key="item.key"
          :item="item"
          :rail="rail"
          :active="isItemActive(item.path)"
        />
      </template>
    </div>

    <!-- Phase2 AC-14: 自動収縮中の説明チップ（スコープページのレール収縮時のみ・レール最下部） -->
    <div
      v-if="showAutoCollapseChip"
      class="flex justify-center border-t border-surface-100 px-1.5 pb-2.5 pt-2 dark:border-surface-800"
    >
      <span
        class="rounded-md bg-primary/10 px-1.5 py-1 text-center text-[10px] font-bold leading-tight text-primary"
      >
        {{ $t('global_nav.autoCollapse.chip') }}
      </span>
    </div>

    <!-- AC-16: モバイルドロワー専用の退避アクション（forceWide=true のときのみ描画）。
         AppHeader.vue はデスクトップ(md=768px)以上でのみこれらを表示するため、
         モバイルではここが唯一の到達導線になる（ハンバーガー1タップ＋各リンク1タップ＝2タップ以内）。 -->
    <div
      v-if="forceWide"
      data-testid="mobile-drawer-actions"
      class="flex flex-col gap-1 border-t border-surface-200 px-2 py-2.5 dark:border-surface-700"
    >
      <div class="flex flex-wrap items-center gap-1 px-1 pb-1">
        <ScopeNavDropdown scope-type="TEAM" :label="t('scopeFolder.nav.teams')" />
        <ScopeNavDropdown scope-type="ORGANIZATION" :label="t('scopeFolder.nav.organizations')" />
      </div>

      <div class="px-2 pb-1">
        <SyncProgressIndicator />
      </div>

      <button
        type="button"
        data-testid="feedback-open-button"
        class="flex items-center gap-3 rounded-lg px-2.5 py-2 text-left text-sm text-surface-600 transition-colors hover:bg-surface-100 dark:text-surface-400 dark:hover:bg-surface-800"
        @click="emit('open-feedback')"
      >
        <i class="pi pi-box text-base text-surface-500 dark:text-surface-400" aria-hidden="true" />
        {{ t('feedback.nav_button') }}
      </button>

      <!-- F04.11: 受信箱への導線（MSH-03: ドロワー経由で到達可能にする） -->
      <NuxtLink
        to="/inbox"
        class="flex items-center gap-3 rounded-lg px-2.5 py-2 text-sm text-surface-600 transition-colors hover:bg-surface-100 dark:text-surface-400 dark:hover:bg-surface-800"
      >
        <i class="pi pi-inbox text-base text-surface-500 dark:text-surface-400" aria-hidden="true" />
        <span class="flex-1">{{ t('inbox.tab.inbox') }}</span>
        <Badge
          v-if="inboxStore.inboxCount > 0"
          :value="inboxStore.inboxCount > 99 ? '99+' : inboxStore.inboxCount"
          severity="danger"
        />
      </NuxtLink>

      <button
        v-if="showPwaInstallBtn"
        type="button"
        class="flex items-center gap-3 rounded-lg px-2.5 py-2 text-left text-sm text-surface-600 transition-colors hover:bg-surface-100 dark:text-surface-400 dark:hover:bg-surface-800"
        @click="handlePwaInstall"
      >
        <i class="pi pi-download text-base text-surface-500 dark:text-surface-400" aria-hidden="true" />
        {{ t('pwa.install_button') }}
      </button>

      <button
        type="button"
        class="flex items-center gap-3 rounded-lg px-2.5 py-2 text-left text-sm text-surface-600 transition-colors hover:bg-surface-100 dark:text-surface-400 dark:hover:bg-surface-800"
        @click="authStore.serverLogout()"
      >
        <i class="pi pi-sign-out text-base text-surface-500 dark:text-surface-400" aria-hidden="true" />
        {{ t('button.logout') }}
      </button>
    </div>
  </nav>
</template>
