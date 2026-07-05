<script setup lang="ts">
const { t } = useI18n()

// プレゼン用の新ランディング（v2）。middleware は付けない
// （guest だと認証済みユーザーが /dashboard に飛ばされプレビューできないため）。
definePageMeta({
  layout: 'landing',
})

useSeoMeta({
  title: () => t('landing.v2.seo_title'),
  robots: 'noindex',
})
</script>

<template>
  <!-- pageTransition out-in の制約によりルートは単一要素の <div> を厳守する -->
  <div>
    <LpHero />
    <LpFeatureChips />
    <LpUseCaseRow />
    <LpFinalCta />
  </div>
</template>
