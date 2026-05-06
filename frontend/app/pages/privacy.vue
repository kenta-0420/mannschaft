<script setup lang="ts">
const { t } = useI18n()

definePageMeta({
  layout: 'landing',
  middleware: 'guest',
})

useSeoMeta({
  title: () => t('landing.legal.privacy.title') + ' - Mannschaft',
  description: () => t('landing.legal.privacy.placeholder'),
})

type SectionKey = '1' | '2' | '3' | '4' | '5'
const sections: SectionKey[] = ['1', '2', '3', '4', '5']
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
          {{ t('landing.legal.privacy.last_updated') }}: 2026-05-06
        </p>

        <div class="mb-8 rounded-xl border border-amber-200 bg-amber-50 p-4 dark:border-amber-800 dark:bg-amber-950">
          <p class="text-sm text-amber-700 dark:text-amber-300">
            {{ t('landing.legal.privacy.placeholder') }}
          </p>
        </div>

        <div class="space-y-8">
          <section
            v-for="section in sections"
            :key="section"
          >
            <h2 class="mb-3 text-lg font-bold text-surface-900 dark:text-white">
              {{ t(`landing.legal.privacy.section${section}_title`) }}
            </h2>
            <p class="leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t(`landing.legal.privacy.section${section}_content`) }}
            </p>
          </section>
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
