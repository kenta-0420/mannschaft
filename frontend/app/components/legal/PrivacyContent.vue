<script setup lang="ts">
/** プライバシーポリシーの本文。ページ（privacy.vue）とモーダル（PrivacyModal.vue）の両方から利用する。 */
const { t } = useI18n()

type SectionKey = '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' | '10' | '11' | '12' | '13' | '14' | '15'
const sections: SectionKey[] = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15']
</script>

<template>
  <div>
    <h1 class="mb-2 text-3xl font-bold text-surface-900 dark:text-white">
      {{ t('landing.legal.privacy.title') }}
    </h1>
    <p class="mb-10 text-sm text-surface-400">
      {{ t('landing.legal.privacy.last_updated') }}: 2026-06-25
    </p>

    <div class="space-y-8">
      <section
        v-for="section in sections"
        :key="section"
      >
        <h2 class="mb-3 text-lg font-bold text-surface-900 dark:text-white">
          {{ t(`landing.legal.privacy.section${section}_title`) }}
        </h2>
        <p class="whitespace-pre-line leading-relaxed text-surface-600 dark:text-surface-300">
          {{ t(`landing.legal.privacy.section${section}_content`) }}
        </p>
      </section>
    </div>

    <div class="mt-10 border-t border-surface-100 pt-6 dark:border-surface-700">
      <p class="text-xs text-surface-400">
        {{ t('landing.legal.privacy.version_notice') }}
      </p>
    </div>
  </div>
</template>
