<script setup lang="ts">
const { t } = useI18n()

// slug は業種別ページ /use-cases/{slug}（LP v2 で10業種に本格化）に対応。
const presets: { key: string; icon: string; slug: string }[] = [
  { key: 'sports_team', icon: 'pi pi-flag', slug: 'sports-team' },
  { key: 'clinic', icon: 'pi pi-heart', slug: 'clinic' },
  { key: 'school', icon: 'pi pi-graduation-cap', slug: 'school' },
  { key: 'alumni', icon: 'pi pi-users', slug: 'alumni' },
  { key: 'salon', icon: 'pi pi-star', slug: 'salon' },
  { key: 'mansion', icon: 'pi pi-building', slug: 'mansion' },
  { key: 'neighborhood', icon: 'pi pi-home', slug: 'neighborhood' },
  { key: 'gym', icon: 'pi pi-bolt', slug: 'gym' },
  { key: 'restaurant', icon: 'pi pi-shopping-bag', slug: 'restaurant' },
  { key: 'circle', icon: 'pi pi-sparkles', slug: 'circle' },
]
</script>

<template>
  <section id="lp-usecases" aria-labelledby="lp-usecases-heading" class="border-y border-surface-200 bg-surface-50 py-16 dark:border-surface-700 dark:bg-surface-800">
    <div class="mx-auto max-w-5xl px-4">
      <div class="mb-8 text-center">
        <h2 id="lp-usecases-heading" class="text-2xl font-bold text-surface-900 dark:text-white md:text-3xl">
          <LpWrapText path="landing.v2.usecases.heading_segments" />
        </h2>
        <p class="mt-3 text-surface-500">
          <LpWrapText path="landing.v2.usecases.subheading_segments" />
        </p>
      </div>
    </div>

    <!-- 横スクロール1行（縦→横変換ハンドラは付けない。ネイティブ横スワイプ＋overscroll-x-contain） -->
    <div class="overflow-x-auto overscroll-x-contain pb-3">
      <div class="mx-auto flex w-max gap-3 px-4 md:px-8">
        <NuxtLink
          v-for="p in presets"
          :key="p.key"
          :to="`/use-cases/${p.slug}`"
          class="flex w-36 shrink-0 flex-col items-center gap-3 rounded-2xl border border-surface-200 bg-white p-5 text-center transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
        >
          <div class="flex h-12 w-12 items-center justify-center rounded-full bg-primary/10">
            <i :class="[p.icon, 'text-xl text-primary']" />
          </div>
          <span class="text-sm font-medium text-surface-800 dark:text-surface-100">
            {{ t(`landing.v2.usecases.${p.key}`) }}
          </span>
        </NuxtLink>
      </div>
    </div>
  </section>
</template>
