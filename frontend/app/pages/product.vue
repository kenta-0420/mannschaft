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

// F20.1 AC-41: 誠実表示原則。¥980/月 等の未確定価格の表示は撤去し、
// 「ベータ期間中は無料・正式料金はベータ終了後に決定」の中立な案内に統一する。
// ログイン済みならプラン一覧（実際の契約状況付き）、未ログインなら登録導線へ。
const authStore = useAuthStore()
const pricingCtaTo = computed(() => (authStore.isAuthenticated ? '/billing/plans' : '/register'))
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

      <!-- 料金プラン（F20.1 AC-41: 誠実表示。未確定価格は表示しない） -->
      <div class="mb-16">
        <h2 class="mb-6 text-center text-2xl font-bold text-surface-900 dark:text-white">
          {{ t('billing.lp.heading') }}
        </h2>
        <div class="mx-auto max-w-2xl rounded-2xl border-2 border-primary bg-primary/5 p-8 text-center dark:bg-primary/10">
          <span class="mb-4 inline-block rounded-full bg-primary px-4 py-1 text-sm font-semibold text-white">
            {{ t('billing.lp.betaBadge') }}
          </span>
          <p class="mx-auto max-w-lg text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('billing.lp.betaNotice') }}
          </p>
          <NuxtLink
            :to="pricingCtaTo"
            class="mt-6 inline-flex items-center gap-2 rounded-xl bg-primary px-6 py-3 font-semibold text-white transition-all duration-200 hover:-translate-y-0.5 hover:bg-primary-600 hover:shadow-lg"
          >
            {{ t('billing.lp.viewPlansCta') }}
            <i class="pi pi-arrow-right" />
          </NuxtLink>
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
