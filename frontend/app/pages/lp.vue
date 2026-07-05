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
  <div>
    <!-- pageTransition out-in の制約により template 直下はこの <div> 1ノードのみ（コメントも置くと非要素根で遷移先が空白になる） -->
    <LpHero />
    <LpFeatureChips />
    <LpUseCaseRow />
    <LpPwaInstall />
    <LpFinalCta />
  </div>
</template>
