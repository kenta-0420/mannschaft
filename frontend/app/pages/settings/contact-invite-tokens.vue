<script setup lang="ts">
const showGuide = ref(false)
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<template>
  <div class="mx-auto max-w-2xl p-6">
    <PageHeader :title="$t('contact_invite.page_title')" help @help="showGuide = true" />
    <p class="mb-6 mt-1 text-sm text-gray-500">
      {{ $t('contact_invite.page_description') }}
    </p>
    <ContactInvitePanel />
    <ContactInviteGuideModal v-model:visible="showGuide" />
  </div>
</template>
