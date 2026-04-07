<script setup lang="ts">
import { defineAsyncComponent, shallowRef } from 'vue'
import type { Component } from 'vue'
import type { EraTheme } from '~/types/eraTheme'

definePageMeta({
  layout: 'landing',
  middleware: 'guest',
})

// SEO/OGP/JSON-LDは常にモダン版の内容をSSR出力（クローラにはモダンを見せる）
const { t, locale } = useI18n()

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

const { pickTheme } = useEraTheme()
const { loadFont } = useEraThemeFonts()

const activeTheme = ref<EraTheme>('modern')

/**
 * テーマIDに対応する非同期コンポーネントを解決する。
 * テンプレートリテラルを使わず固定文字列のみでswitch/caseを構成する（パストラバーサル対策）。
 * modernのみ初回バンドルに含め、レトロ8種は別chunkとして遅延ロードする（パフォーマンス最適化）。
 */
function resolveThemeComponent(theme: EraTheme): Component {
  switch (theme) {
    case 'y1998':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingTheme1998.vue'))
    case 'y2000':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingTheme2000.vue'))
    case 'y2005':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingTheme2005.vue'))
    case 'y2010':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingTheme2010.vue'))
    case 'y2015':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingTheme2015.vue'))
    case 'y2020':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingTheme2020.vue'))
    case 'fc':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingThemeFc.vue'))
    case 'sfc':
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingThemeSfc.vue'))
    case 'modern':
    default:
      return defineAsyncComponent(() => import('~/components/landing/themes/LandingThemeModern.vue'))
  }
}

// 初期値はModern（onMountedでpickTheme()により上書きされる）
const themeComponent = shallowRef<Component>(
  defineAsyncComponent(() => import('~/components/landing/themes/LandingThemeModern.vue')),
)

function applyTheme(theme: EraTheme) {
  activeTheme.value = theme
  themeComponent.value = resolveThemeComponent(theme)
  loadFont(theme)
}

onMounted(() => {
  // クライアント側でテーマを抽選・適用
  applyTheme(pickTheme())
})
</script>

<template>
  <div>
    <!--
      ClientOnly: SSR時はfallbackのモダン版を表示し、クライアントでテーマを切り替える。
      これによりハイドレーション不一致を防ぎ、SEO/OGPをモダン版に固定する。
    -->
    <ClientOnly>
      <component :is="themeComponent" />
      <!-- レトロテーマ表示中のみ脱出ボタンを表示 -->
      <LandingEraThemeSwitcher
        v-if="activeTheme !== 'modern'"
        @switch-to-modern="applyTheme('modern')"
      />
      <template #fallback>
        <!-- SSR時はモダン版を直接表示（クローラ・非JS環境向け） -->
        <LandingThemeModern />
      </template>
    </ClientOnly>
  </div>
</template>
