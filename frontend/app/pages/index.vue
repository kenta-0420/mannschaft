<script setup lang="ts">
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
      innerHTML: JSON.stringify({
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
      }),
    },
  ],
})
</script>

<template>
  <div>
    <LandingHero />
    <LandingStats />
    <LandingFeatures />
    <LandingSteps />
    <LandingUseCases />
    <LandingFaq />
    <LandingCta />
  </div>
</template>
