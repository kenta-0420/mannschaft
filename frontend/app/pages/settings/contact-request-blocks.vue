<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <div class="mb-6">
      <h1 class="text-2xl font-bold">申請事前拒否リスト</h1>
      <p class="mt-1 text-sm text-gray-500">
        リストに登録したユーザーからの連絡先追加申請を自動的に無視します。
      </p>
    </div>
    <ContactRequestBlockList />
  </div>
</template>
