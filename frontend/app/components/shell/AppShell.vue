<script setup lang="ts">
/**
 * サイドバー化 Phase3: 新シェル（ヘッダー＋グローバルサイドバー＋main）。
 *
 * layouts/default.vue から常時描画される（Phase1/2 の app-shell-enabled フラグ・
 * 旧上部ナビ〔横スクロールnav/旧モバイルDrawer〕は Phase3 で撤去済み）。
 *
 * スロット:
 * - default: ページ本体
 * - scope-sidebar: チーム/組織の2本目サイドバー用に予約済みだが、現状 team.vue/organization.vue
 *   は自前の <aside> を this の default スロット内（NuxtLayout name="default" 経由）に描画しており未使用
 * - header-actions: ヘッダー右端の追加アクション（既存 default.vue の同名スロットを中継）
 */
defineEmits<{
  'open-quick-memo': []
  'open-feedback': []
  'open-ios-install': []
}>()

const appShellStore = useAppShellStore()
const route = useRoute()

// Phase2 AC-14: スコープ（チーム/組織）ページ滞在中の自動レール収縮。
// 「現在ルートがスコープ配下か」を単一の判定源として forceRail を同期する
// （mount/unmount 結線の順序競合を避ける。詳細は useScopeAutoRail.ts のコメント参照）。
useScopeAutoRail()

// Phase2 AC-5: ルート遷移でモバイルDrawerを自動クローズする
// （現行 default.vue の showMobileMenu と同型。遷移後に開きっぱなしになる回帰の根治）。
watch(() => route.path, () => {
  appShellStore.closeMobileDrawer()
})

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

    <div class="flex items-stretch" style="min-height: calc(100vh - var(--app-header-h))">
      <GlobalSidebar v-if="!isMobileViewport" />
      <ClientOnly>
        <!-- UX改善: モバイルDrawerはヘッダー直下から出す（ヘッダーを覆わない）。
             理由: PrimeVue Drawer既定はマスク/パネルが top:0 ・ z-index 1100+ で全画面を覆い、
             sticky z-50 のヘッダー（開閉トグルボタン含む）がその下に埋もれてクリック不能になる
             （aria-label「メニューを閉じる」なのに再クリックで閉じられない回帰）。
             base-z-index をヘッダーの z-50 未満にし、mask の top/height を pt で上書きして
             ヘッダー分だけ開始位置を下げることで、ヘッダー（トグルボタン）を常時可視・操作可能に保つ。
             他のDrawer（team.vue/organization.vue/ScopePageShell 等）はヘッダー常設トグルを
             持たないため対象外（Escape/マスククリック/自身のXボタンで閉じられる既存動作のまま）。 -->
        <Drawer
          v-if="isMobileViewport"
          v-model:visible="appShellStore.mobileDrawerOpen"
          position="left"
          class="!w-72"
          :base-z-index="10"
          :pt="{
            mask: {
              style: {
                top: 'var(--app-header-h)',
                height: 'calc(100% - var(--app-header-h))',
              },
            },
          }"
        >
          <GlobalSidebar
            force-wide
            @open-feedback="$emit('open-feedback')"
            @open-ios-install="$emit('open-ios-install')"
          />
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
