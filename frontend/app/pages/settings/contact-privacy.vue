<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <PageHeader title="連絡先プライバシー設定" />
    <p class="mb-6 mt-1 text-sm text-gray-500">
      連絡先追加・DM・オンライン状態に関するプライバシーを設定します
    </p>
    <ContactPrivacyForm />
  </div>
</template>
