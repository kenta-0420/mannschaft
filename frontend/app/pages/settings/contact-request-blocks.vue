<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <PageHeader title="申請事前拒否リスト" />
    <p class="mb-6 mt-1 text-sm text-gray-500">
      リストに登録したユーザーからの連絡先追加申請を自動的に無視します。
    </p>
    <ContactRequestBlockList />
  </div>
</template>
