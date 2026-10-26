<script setup lang="ts">
const { t } = useI18n()

// 公開情報ページのため middleware は付けない
// （guest だと認証済みユーザーが /dashboard に飛ばされ、特定商取引法に基づく表記を読めなくなる）
definePageMeta({
  layout: 'landing',
})

useSeoMeta({
  title: () => t('landing.legal.commerce.title') + ' - Mannschaft',
  description: () => t('landing.legal.commerce.seo_description'),
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

      <div class="rounded-2xl bg-white p-6 shadow-sm dark:bg-surface-800 md:p-10">
        <CommerceDisclosureContent />
      </div>

      <!-- リンク -->
      <div class="mt-8 flex flex-col items-center gap-2 text-center">
        <NuxtLink
          to="/terms"
          class="text-sm text-primary hover:underline"
        >
          {{ t('landing.layout.footer_terms') }} →
        </NuxtLink>
        <NuxtLink
          to="/privacy"
          class="text-sm text-primary hover:underline"
        >
          {{ t('landing.layout.footer_privacy') }} →
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
