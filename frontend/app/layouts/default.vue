<script setup lang="ts">
const authStore = useAuthStore()
const route = useRoute()

const isMounted = ref(false)
const showBlogCreate = ref(false)
const showMobileMenu = ref(false)

onMounted(() => {
  isMounted.value = true
})

watch(
  () => route.path,
  () => {
    showMobileMenu.value = false
  },
)

const navItems = computed(() => [
  { label: 'ダッシュボード', icon: 'pi pi-home', to: '/dashboard' },
  { label: 'チーム', icon: 'pi pi-users', to: '/teams' },
  { label: '組織', icon: 'pi pi-building', to: '/organizations' },
  { label: 'TODO', icon: 'pi pi-check-square', to: '/todos' },
  { label: 'カレンダー', icon: 'pi pi-calendar', to: '/calendar' },
  { label: 'タイムライン', icon: 'pi pi-comments', to: '/timeline' },
  { label: 'チャット', icon: 'pi pi-comment', to: '/chat' },
  { label: 'マイページ', icon: 'pi pi-user', to: '/my/onboarding' },
  { label: '設定', icon: 'pi pi-cog', to: '/settings' },
])

const systemAdminItem = { label: 'SYSTEM', icon: 'pi pi-shield', to: '/system-admin' }

function isActive(path: string): boolean {
  return route.path.startsWith(path)
}
</script>

<template>
  <!-- マウント前（SSR含む）はスピナーのみ表示してフラッシュを防ぐ -->
  <div v-if="!isMounted" class="flex min-h-screen items-center justify-center bg-surface-ground">
    <ProgressSpinner style="width: 48px; height: 48px" />
  </div>

  <div v-else class="min-h-screen bg-surface-ground">
    <!-- ヘッダー -->
    <header class="bg-surface-0 border-b border-surface shadow-sm">
      <div class="mx-auto flex h-16 max-w-screen-2xl items-center justify-between px-4">
        <!-- 左: ロゴ + ナビゲーション -->
        <div class="flex min-w-0 flex-1 items-center gap-6">
          <NuxtLink to="/dashboard" class="text-3xl font-bold text-primary"> Mannschaft </NuxtLink>
          <ClientOnly>
            <nav
              v-if="authStore.isAuthenticated"
              class="hidden md:flex items-center gap-1 overflow-x-auto scrollbar-hide"
            >
              <NuxtLink
                v-for="item in navItems"
                :key="item.to"
                :to="item.to"
                class="flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-surface-100"
                :class="isActive(item.to) ? 'bg-primary/10 text-primary' : 'text-surface-600'"
              >
                <i :class="item.icon" />
                {{ item.label }}
              </NuxtLink>
              <NuxtLink
                v-if="authStore.isSystemAdmin"
                :to="systemAdminItem.to"
                class="flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-red-50"
                :class="isActive(systemAdminItem.to) ? 'bg-red-100 text-red-600' : 'text-red-500'"
              >
                <i :class="systemAdminItem.icon" />
                {{ systemAdminItem.label }}
              </NuxtLink>
              <!-- ブログはモーダル起動 -->
              <button
                class="flex shrink-0 items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors hover:bg-surface-100 text-surface-600"
                @click="showBlogCreate = true"
              >
                <i class="pi pi-book" />
                ブログ
              </button>
            </nav>
          </ClientOnly>
        </div>

        <!-- 右: ユーザーメニュー -->
        <div class="flex items-center gap-3">
          <ClientOnly>
            <template v-if="authStore.isAuthenticated">
              <NotificationBell />
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

    <!-- メインコンテンツ -->
    <main class="mx-auto max-w-screen-2xl p-4">
      <slot />
    </main>

    <ClientOnly>
      <ErrorReportDialog />
      <BlogCreateDialog v-model:visible="showBlogCreate" />

      <!-- モバイルメニュー Drawer -->
      <Drawer v-model:visible="showMobileMenu" position="left" class="w-72">
        <template #header>
          <span class="text-xl font-bold text-primary">Mannschaft</span>
        </template>
        <nav class="flex flex-col gap-1 pt-2">
          <NuxtLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="flex items-center gap-3 rounded-lg px-4 py-3 text-sm font-medium transition-colors hover:bg-surface-100"
            :class="isActive(item.to) ? 'bg-primary/10 text-primary' : 'text-surface-700'"
          >
            <i :class="[item.icon, 'text-base']" />
            {{ item.label }}
          </NuxtLink>
          <!-- ブログ -->
          <button
            class="flex items-center gap-3 rounded-lg px-4 py-3 text-sm font-medium text-surface-700 transition-colors hover:bg-surface-100 text-left"
            @click="
              showMobileMenu = false
              showBlogCreate = true
            "
          >
            <i class="pi pi-book text-base" />
            ブログ
          </button>
          <!-- システム管理 -->
          <NuxtLink
            v-if="authStore.isSystemAdmin"
            :to="systemAdminItem.to"
            class="flex items-center gap-3 rounded-lg px-4 py-3 text-sm font-medium transition-colors hover:bg-red-50"
            :class="isActive(systemAdminItem.to) ? 'bg-red-100 text-red-600' : 'text-red-500'"
          >
            <i :class="[systemAdminItem.icon, 'text-base']" />
            {{ systemAdminItem.label }}
          </NuxtLink>
        </nav>
        <div class="mt-4 border-t border-surface-200 pt-4">
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
