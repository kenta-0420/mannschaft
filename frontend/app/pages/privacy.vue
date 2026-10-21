<script setup lang="ts">
import { PRIVACY_POLICY_VERSION } from '~/constants/legalPolicy'

const { t } = useI18n()

definePageMeta({
  layout: 'landing',
})

useSeoMeta({
  title: () => t('landing.legal.privacy.title') + ' - Mannschaft',
  description: () => t('landing.legal.privacy.version_notice', { version: PRIVACY_POLICY_VERSION }),
})
</script>

<template>
  <div class="min-h-screen bg-surface-50 dark:bg-surface-900">
    <div class="mx-auto max-w-3xl px-4 py-12">
      <!-- 戻るリンク -->
      <div class="mb-8">
        <NuxtLink
          to="/"
          class="inline-flex items-center gap-2 text-sm text-surface-500 transition-colors hover:text-primary"
        >
          {{ t('landing.features_detail.back_to_top') }}
        </NuxtLink>
      </div>

      <div class="rounded-2xl bg-white p-10 shadow-sm dark:bg-surface-800">
        <PrivacyContent />
      </div>

      <!-- リンク -->
      <div class="mt-8 text-center">
        <NuxtLink
          to="/terms"
          class="text-sm text-primary hover:underline"
        >
          {{ t('landing.layout.footer_terms') }} →
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
