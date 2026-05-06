<script setup lang="ts">
const { t } = useI18n()

definePageMeta({
  layout: 'landing',
  middleware: 'guest',
})

useSeoMeta({
  title: () => t('landing.product.title') + ' - Mannschaft',
  description: () => t('landing.product.desc'),
})

const features = [
  { icon: 'pi pi-users', key: 0, slug: 'team' },
  { icon: 'pi pi-calendar', key: 1, slug: 'calendar' },
  { icon: 'pi pi-comments', key: 2, slug: 'chat' },
  { icon: 'pi pi-clock', key: 3, slug: 'shift' },
  { icon: 'pi pi-file-edit', key: 4, slug: 'forms' },
  { icon: 'pi pi-images', key: 5, slug: 'gallery' },
  { icon: 'pi pi-credit-card', key: 6, slug: 'billing' },
  { icon: 'pi pi-trophy', key: 7, slug: 'matching' },
]

type PlanKey = 'free' | 'pro' | 'enterprise'

const pricingPlans: PlanKey[] = ['free', 'pro', 'enterprise']
const pricingHighlight: Record<PlanKey, boolean> = {
  free: false,
  pro: true,
  enterprise: false,
}
</script>

<template>
  <div class="min-h-screen bg-surface-50 dark:bg-surface-900">
    <div class="mx-auto max-w-6xl px-4 py-12">
      <!-- 戻るリンク -->
      <div class="mb-8">
        <NuxtLink
          to="/"
          class="inline-flex items-center gap-2 text-sm text-surface-500 transition-colors hover:text-primary"
        >
          ← トップページへ
        </NuxtLink>
      </div>

      <!-- ヒーロー -->
      <div class="mb-16 text-center">
        <h1 class="mb-4 text-4xl font-bold text-surface-900 dark:text-white md:text-5xl">
          {{ t('landing.product.title') }}
        </h1>
        <p class="mx-auto max-w-2xl text-lg text-surface-500">
          {{ t('landing.product.desc') }}
        </p>
      </div>

      <!-- 主要機能 -->
      <div class="mb-16">
        <h2 class="mb-10 text-center text-2xl font-bold text-surface-900 dark:text-white">
          {{ t('landing.product.features_heading') }}
        </h2>
        <div class="grid grid-cols-2 gap-4 md:grid-cols-4">
          <NuxtLink
            v-for="f in features"
            :key="f.key"
            :to="`/features/${f.slug}`"
            class="rounded-xl border border-surface-200 bg-white p-5 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
          >
            <i :class="[f.icon, 'mb-3 block text-2xl text-primary']" />
            <div class="mb-1 font-semibold text-surface-800 dark:text-white">
              {{ t(`landing.features.items.${f.key}.title`) }}
            </div>
            <div class="text-sm leading-relaxed text-surface-500">
              {{ t(`landing.features.items.${f.key}.desc`) }}
            </div>
          </NuxtLink>
        </div>
      </div>

      <!-- 料金プラン -->
      <div class="mb-16">
        <h2 class="mb-10 text-center text-2xl font-bold text-surface-900 dark:text-white">
          {{ t('landing.product.pricing_heading') }}
        </h2>
        <div class="grid gap-6 md:grid-cols-3">
          <div
            v-for="plan in pricingPlans"
            :key="plan"
            :class="[
              'rounded-2xl p-8',
              pricingHighlight[plan]
                ? 'border-2 border-primary bg-primary text-white shadow-xl'
                : 'border border-surface-200 bg-white dark:border-surface-700 dark:bg-surface-800',
            ]"
          >
            <div class="mb-2 text-lg font-bold">
              {{ t(`landing.product.pricing.${plan}.name`) }}
            </div>
            <div class="mb-1 text-3xl font-extrabold">
              {{ t(`landing.product.pricing.${plan}.price`) }}
            </div>
            <div
              :class="[
                'mb-6 text-sm',
                pricingHighlight[plan] ? 'text-primary-100' : 'text-surface-500',
              ]"
            >
              {{ t(`landing.product.pricing.${plan}.desc`) }}
            </div>
            <ul class="space-y-2">
              <li
                v-for="(item, idx) in (t(`landing.product.pricing.${plan}.features`) as unknown as string[])"
                :key="idx"
                class="flex items-center gap-2 text-sm"
              >
                <i
                  :class="[
                    'pi pi-check shrink-0',
                    pricingHighlight[plan] ? 'text-white' : 'text-primary',
                  ]"
                />
                {{ item }}
              </li>
            </ul>
            <NuxtLink
              to="/register"
              :class="[
                'mt-8 block rounded-xl px-6 py-3 text-center font-semibold transition-all duration-200',
                pricingHighlight[plan]
                  ? 'bg-white text-primary hover:bg-primary-50'
                  : 'bg-primary text-white hover:bg-primary-600',
              ]"
            >
              {{ t('landing.cta.register') }}
            </NuxtLink>
          </div>
        </div>
      </div>

      <!-- CTA -->
      <div class="rounded-2xl bg-primary p-12 text-center text-white">
        <h2 class="mb-4 text-2xl font-bold">
          {{ t('landing.cta.heading') }}
        </h2>
        <p class="mb-8 text-primary-100">
          {{ t('landing.cta.subheading') }}
        </p>
        <NuxtLink
          to="/register"
          class="inline-flex items-center gap-2 rounded-xl bg-white px-8 py-4 text-lg font-semibold text-primary shadow-lg transition-all duration-200 hover:-translate-y-0.5 hover:shadow-xl"
        >
          {{ t('landing.cta.register') }}
          <i class="pi pi-arrow-right" />
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
