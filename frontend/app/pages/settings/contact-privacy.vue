<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { t } = useI18n()
const showGuide = ref(false)
</script>

<template>
  <div>
    <div class="mx-auto max-w-2xl p-6">
      <PageHeader :title="t('contact_privacy.title')" help @help="showGuide = true" />
      <p class="mb-6 text-sm text-surface-500 dark:text-surface-300">
        {{ t('contact_privacy.description') }}
      </p>
      <ContactPrivacyForm />
      <ContactPrivacyGuideModal v-model:visible="showGuide" />
    </div>
  </div>
</template>
