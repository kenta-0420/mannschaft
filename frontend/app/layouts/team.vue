<script setup lang="ts">
const route = useRoute()
const showSidebar = ref(false)

// teamId を route params から取得
const teamId = computed(() => {
  const id = route.params.id
  return id ? String(Array.isArray(id) ? id[0] : id) : null
})

// ルート変更時にDrawerを閉じる
watch(() => route.path, () => {
  showSidebar.value = false
})
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
