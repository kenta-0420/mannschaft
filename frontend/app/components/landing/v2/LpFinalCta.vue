<script setup lang="ts">
const { t } = useI18n()

const faqIds = [0, 1, 2, 3, 4, 5, 6, 7]
</script>

<template>
  <section id="lp-cta" aria-labelledby="lp-cta-heading" class="py-20 dark:bg-surface-900">
    <div class="mx-auto max-w-2xl px-4 text-center">
      <h2 id="lp-cta-heading" class="text-3xl font-black text-surface-900 dark:text-white md:text-4xl">
        <LpWrapText path="landing.v2.cta.heading_segments" />
      </h2>
      <p class="mt-4 text-surface-600 dark:text-surface-300">
        <LpWrapText path="landing.v2.cta.subheading_segments" />
      </p>

      <div class="mt-8 flex flex-col items-center gap-2">
        <NuxtLink to="/register">
          <Button
            :label="t('landing.v2.cta.register')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            size="large"
            class="px-10"
          />
        </NuxtLink>
        <p class="text-sm text-surface-500">
          {{ t('landing.v2.cta.pricing_prompt') }}
          <NuxtLink to="/product" class="font-medium text-primary hover:underline">
            {{ t('landing.v2.cta.pricing_link') }}
          </NuxtLink>
        </p>
      </div>

      <!-- FAQ（アコーディオン閉状態で8件・シンプルさは維持） -->
      <div class="mt-14 text-left">
        <h3 class="mb-4 text-center text-lg font-bold text-surface-800 dark:text-surface-100">
          {{ t('landing.v2.cta.faq_heading') }}
        </h3>
        <div class="space-y-2">
          <details
            v-for="id in faqIds"
            :key="id"
            class="group rounded-xl border border-surface-200 bg-white px-4 py-3 dark:border-surface-700 dark:bg-surface-800"
          >
            <summary
              class="flex cursor-pointer list-none items-center justify-between gap-3 text-sm font-medium text-surface-800 dark:text-surface-100"
            >
              {{ t(`landing.v2.cta.faq.${id}.q`) }}
              <i class="pi pi-chevron-down shrink-0 text-xs text-surface-400 transition-transform duration-200 group-open:rotate-180" />
            </summary>
            <p class="mt-2.5 text-sm leading-relaxed text-surface-500">
              {{ t(`landing.v2.cta.faq.${id}.a`) }}
            </p>
          </details>
        </div>
      </div>

      <!-- 遊び心（レトロテーマへの匂わせ・発動処理は実装しない） -->
      <p class="mt-12 text-xs text-surface-400">
        {{ t('landing.v2.cta.retro_hint') }}
      </p>
    </div>
  </section>
</template>
