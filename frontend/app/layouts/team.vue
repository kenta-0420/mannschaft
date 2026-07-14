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
// この <aside>＋TeamSidebar／sticky top-16／calc(100vh - 4rem) 自体は無改修（既存仕様のまま）。
</script>

<template>
  <NuxtLayout name="default">
    <div class="flex" style="min-height: calc(100vh - 4rem)">
      <!-- デスクトップサイドバー（lg以上で常時表示） -->
      <aside
        v-if="teamId"
        class="hidden lg:flex flex-col w-[260px] shrink-0 border-r border-surface-200 bg-surface-0 sticky top-16 overflow-y-auto"
        style="height: calc(100vh - 4rem)"
      >
        <TeamSidebar :team-id="teamId" />
      </aside>

      <!-- モバイル用 Drawer -->
      <Drawer v-model:visible="showSidebar" position="left" class="!w-72">
        <template #header>
          <span class="font-semibold">メニュー</span>
        </template>
        <TeamSidebar v-if="teamId" :team-id="teamId" />
      </Drawer>

      <!-- メインコンテンツ -->
      <main class="flex-1 min-w-0">
        <!-- モバイル: サイドバー開閉ボタン -->
        <div class="lg:hidden px-4 pt-3">
          <Button
            icon="pi pi-bars"
            text
            size="small"
            @click="showSidebar = true"
          />
        </div>
        <slot />
      </main>
    </div>
  </NuxtLayout>
</template>
