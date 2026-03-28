<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const settingsItems = [
  { label: 'プロフィール', description: 'プロフィール情報・パスワードの管理', icon: 'pi pi-user', to: '/settings/profile' },
  { label: '外観', description: 'テーマ・背景色・表示設定', icon: 'pi pi-palette', to: '/settings/appearance' },
  { label: 'QR会員証', description: '会員証の表示・チェックイン履歴', icon: 'pi pi-id-card', to: '/settings/member-cards' },
  { label: 'ソーシャルプロフィール', description: '匿名プロフィール・フォロー管理', icon: 'pi pi-users', to: '/settings/social-profiles' },
  { label: '電子印鑑', description: '印鑑の表示・スコープ別設定', icon: 'pi pi-verified', to: '/settings/seals' },
  { label: '通知', description: '通知の受け取り設定', icon: 'pi pi-bell', to: '/settings/notifications' },
  { label: 'Google Calendar', description: 'カレンダー同期・iCal連携', icon: 'pi pi-google', to: '/settings/calendar-sync' },
]
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <h1 class="mb-6 text-2xl font-bold">設定</h1>
    <div class="space-y-3">
      <NuxtLink
        v-for="item in settingsItems"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-4 rounded-xl border border-surface-200 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
      >
        <div class="flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <i :class="item.icon" class="text-xl" />
        </div>
        <div>
          <p class="font-medium">{{ item.label }}</p>
          <p class="text-sm text-surface-500">{{ item.description }}</p>
        </div>
        <i class="pi pi-chevron-right ml-auto text-surface-400" />
      </NuxtLink>
    </div>
  </div>
</template>
