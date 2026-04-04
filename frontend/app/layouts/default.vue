<script setup lang="ts">
const authStore = useAuthStore()
const route = useRoute()

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

function isActive(path: string): boolean {
  return route.path.startsWith(path)
}
</script>

<template>
  <div class="min-h-screen bg-surface-ground">
    <!-- ヘッダー -->
    <header class="bg-surface-0 border-b border-surface shadow-sm">
      <div class="mx-auto flex h-16 max-w-screen-2xl items-center justify-between px-4">
        <!-- 左: ロゴ + ナビゲーション -->
        <div class="flex min-w-0 flex-1 items-center gap-6">
          <NuxtLink to="/dashboard" class="text-3xl font-bold text-primary"> Mannschaft </NuxtLink>
          <nav
            v-if="authStore.isAuthenticated"
            class="flex items-center gap-1 overflow-x-auto scrollbar-hide"
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
          </nav>
        </div>

        <!-- 右: スコープセレクター + ユーザーメニュー -->
        <div class="flex items-center gap-3">
          <NuxtLink
            v-if="authStore.isAuthenticated"
            v-tooltip.bottom="'検索'"
            to="/search"
            class="flex items-center"
          >
            <Button icon="pi pi-search" text rounded severity="secondary" />
          </NuxtLink>
          <NuxtLink
            v-if="authStore.isAuthenticated"
            v-tooltip.bottom="'メンション'"
            to="/mentions"
            class="flex items-center"
          >
            <Button icon="pi pi-at" text rounded severity="secondary" />
          </NuxtLink>
          <ScopeSelector v-if="authStore.isAuthenticated" />
          <NotificationBell v-if="authStore.isAuthenticated" />
          <Button
            v-if="authStore.isAuthenticated"
            v-tooltip.bottom="'ログアウト'"
            icon="pi pi-sign-out"
            text
            rounded
            severity="secondary"
            @click="authStore.serverLogout()"
          />
          <slot name="header-actions" />
        </div>
      </div>
    </header>

    <!-- メインコンテンツ -->
    <main class="mx-auto max-w-screen-2xl p-4">
      <slot />
    </main>
  </div>
</template>
