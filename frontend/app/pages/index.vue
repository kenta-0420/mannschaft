<script setup lang="ts">
import { escapeJsonLdForHtml } from '~/utils/escapeJsonLdForHtml'

const { t, locale } = useI18n()

definePageMeta({
  layout: 'landing',
  middleware: 'guest',
})

useSeoMeta({
  title: () => t('landing.seo.title'),
  ogTitle: () => t('landing.seo.og_title'),
  description: () => t('landing.seo.description'),
  ogDescription: () => t('landing.seo.og_description'),
  ogType: 'website',
  ogLocale: () => locale.value,
  twitterCard: 'summary_large_image',
})

useHead({
  script: [
    {
      type: 'application/ld+json',
      // F21.1 セキュリティ（XSS 対策）: 現状は静的値のみだが、JSON-LD 注入箇所は
      // 一律で小なり記号をエスケープし、将来の動的化でもブレイクアウトを防ぐ。
      innerHTML: escapeJsonLdForHtml(JSON.stringify({
        '@context': 'https://schema.org',
        '@type': 'SoftwareApplication',
        'name': 'Mannschaft',
        'applicationCategory': 'BusinessApplication',
        'operatingSystem': 'Web',
        'offers': {
          '@type': 'Offer',
          'price': '0',
          'priceCurrency': 'JPY',
        },
      })),
    },
  ],
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
