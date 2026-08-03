<script setup lang="ts">
const route = useRoute()
const showSidebar = ref(false)

// teamId（slug）を route params から取得
// [slug] ルート配下では params.id は undefined になるため params.slug を読む
const teamId = computed(() => {
  const slug = route.params.slug
  return slug ? String(Array.isArray(slug) ? slug[0] : slug) : null
})

// ルート変更時にDrawerを閉じる
watch(() => route.path, () => {
  showSidebar.value = false
})

// サイドバー化 Phase2 AC-14: チーム画面滞在中のグローバルサイドバー自動レール収縮は
// AppShell 内の useScopeAutoRail()（「現在ルートがスコープ配下か」の単一判定源）が担う。
// 本レイアウトでの onMounted/onUnmounted 結線は行わない — 永続シェル
// （pages/teams/[slug].vue・ScopePageShell）とのレイアウト切替時に unmount の
// setForceRail(false) が正しい状態を上書きする順序競合を生むため。
//
// サイドバー撤去: デスクトップ固定 <aside> を廃止し、全画面サイズでハンバーガー→Drawer
// 方式に統一（ScopePageShell と同一パターン）。SPA 遷移感の改善と画面スペース確保のため。
</script>

<template>
  <NuxtLayout name="default">
    <div class="flex" style="min-height: calc(100vh - var(--app-header-h))">
      <!-- 全画面用 Drawer サイドバー（固定 <aside> 撤去・ScopePageShell と同一パターン） -->
      <Drawer v-model:visible="showSidebar" position="left" class="!w-72">
        <template #header>
          <span class="font-semibold">メニュー</span>
        </template>
        <TeamSidebar v-if="teamId" :team-id="teamId" />
      </Drawer>

      <!-- メインコンテンツ -->
      <main class="flex-1 min-w-0">
        <!-- サイドバー開閉ボタン（全画面サイズで表示） -->
        <div class="px-4 pt-3">
          <Button
            icon="pi pi-bars"
            text
            size="small"
            :aria-label="$t('common.menu')"
            data-testid="scope-sidebar-toggle"
            @click="showSidebar = true"
          />
        </div>
        <slot />
      </main>
    </div>
  </NuxtLayout>
</template>
