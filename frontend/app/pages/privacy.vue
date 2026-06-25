<script setup lang="ts">
const { t } = useI18n()

definePageMeta({
  layout: 'landing',
})

useSeoMeta({
  title: () => t('landing.legal.privacy.title') + ' - Mannschaft',
  description: () => t('landing.legal.privacy.version_notice'),
})

type SectionKey = '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' | '10' | '11' | '12' | '13' | '14' | '15'
const sections: SectionKey[] = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15']
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
