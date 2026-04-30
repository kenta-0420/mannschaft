<script setup lang="ts">
definePageMeta({
  layout: 'auth',
  middleware: 'auth',
})
</script>

<template>
  <div class="flex flex-col items-center gap-6 text-center">
    <i class="pi pi-check-circle text-5xl text-green-500" />

    <div class="flex flex-col gap-2">
      <h2 class="text-xl font-bold">退会が取り消されました</h2>
      <p class="text-surface-500">
        退会処理中のアカウントでログインしたため、退会を自動的に取り消しました。<br />
        引き続きご利用いただけます。
      </p>
    </div>

    <div class="flex w-full flex-col gap-3">
      <Button
        label="ホームへ"
        icon="pi pi-home"
        class="w-full"
        @click="navigateTo('/')"
      />
      <Button
        label="アカウント設定"
        icon="pi pi-cog"
        severity="secondary"
        outlined
        class="w-full"
        @click="navigateTo('/settings/account')"
      />
    </div>

    <p class="text-xs text-surface-400">
      再度退会を希望する場合は、アカウント設定から手続きを行ってください。
    </p>
  </div>
</template>
