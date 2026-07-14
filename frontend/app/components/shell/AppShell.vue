<script setup lang="ts">
/**
 * サイドバー化 Phase1: 新シェル（ヘッダー＋グローバルサイドバー＋main）。
 *
 * layouts/default.vue から localStorage['app-shell-enabled'] === 'true' の場合のみ
 * 描画される（既定OFF・現行 default.vue マークアップは無傷のまま併存）。
 *
 * スロット:
 * - default: ページ本体
 * - scope-sidebar: Phase2 用（チーム/組織の2本目サイドバー）。Phase1 では未使用
 * - header-actions: ヘッダー右端の追加アクション（既存 default.vue の同名スロットを中継）
 */
defineEmits<{
  'open-quick-memo': []
  'open-feedback': []
  'open-ios-install': []
}>()

const appShellStore = useAppShellStore()

// モバイル（<md）判定。GlobalSidebar を「デスクトップ常時表示」と「モバイルDrawer内描画」の
// どちらか一方だけマウントするために使う（両方を同時にマウントすると
// data-testid="global-sidebar" が2要素マッチし、既存/将来のE2Eセレクタを壊すため）。
const isMobileViewport = ref(false)
let mql: MediaQueryList | null = null
function syncViewport() {
  isMobileViewport.value = mql ? mql.matches : false
}

onMounted(() => {
  appShellStore.loadFromStorage()
  mql = window.matchMedia('(max-width: 767px)')
  syncViewport()
  mql.addEventListener('change', syncViewport)
})
onUnmounted(() => {
  mql?.removeEventListener('change', syncViewport)
})
</script>

<template>
  <div>
    <AppHeader
      @open-quick-memo="$emit('open-quick-memo')"
      @open-feedback="$emit('open-feedback')"
      @open-ios-install="$emit('open-ios-install')"
    >
      <template #header-actions>
        <slot name="header-actions" />
      </template>
    </AppHeader>

    <!-- バナー類（オフライン通知・後見切替中バナー等）。ヘッダー直下・本体グリッドの上 -->
    <slot name="banners" />

    <div class="flex items-stretch" style="min-height: calc(100vh - 4rem)">
      <GlobalSidebar v-if="!isMobileViewport" />
      <ClientOnly>
        <Drawer
          v-if="isMobileViewport"
          v-model:visible="appShellStore.mobileDrawerOpen"
          position="left"
          class="!w-72"
        >
          <GlobalSidebar force-wide />
        </Drawer>
      </ClientOnly>

      <!-- Phase2用: スコープサイドバー（チーム/組織の2本目）。Phase1では未使用 -->
      <slot name="scope-sidebar" />

      <main class="min-w-0 flex-1 p-4">
        <slot />
      </main>
    </div>
  </div>
</template>
