<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <PageHeader title="招待URL管理" />
    <p class="mb-6 mt-1 text-sm text-gray-500">
      連絡先追加用の招待URLを発行・管理します。URLを共有した相手があなたを連絡先に追加できます。
    </p>
    <ContactInvitePanel />
  </div>
</template>
