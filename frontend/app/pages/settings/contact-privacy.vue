<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <div class="mb-6">
      <h1 class="text-2xl font-bold">連絡先プライバシー設定</h1>
      <p class="mt-1 text-sm text-gray-500">
        連絡先追加・DM・オンライン状態に関するプライバシーを設定します
      </p>
    </div>
    <ContactPrivacyForm />
  </div>
</template>
