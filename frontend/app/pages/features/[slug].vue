<script setup lang="ts">
const { t, tm, rt } = useI18n()
const route = useRoute()

const slugs = ['team', 'calendar', 'chat', 'shift', 'forms', 'gallery', 'billing', 'matching']
const icons = [
  'pi pi-users',
  'pi pi-calendar',
  'pi pi-comments',
  'pi pi-clock',
  'pi pi-file-edit',
  'pi pi-images',
  'pi pi-credit-card',
  'pi pi-trophy',
]

const slug = route.params.slug as string
const featureKey = slugs.indexOf(slug)

if (featureKey === -1) {
  throw createError({ statusCode: 404, statusMessage: 'Feature not found' })
}

// 公開情報ページのため middleware は付けない
// （guest だと認証済みユーザーが /dashboard に飛ばされ、LPからの段階開示導線が壊れる）
definePageMeta({
  layout: 'landing',
})

const title = computed(() => t(`landing.features.items.${featureKey}.title`))
const desc = computed(() => t(`landing.features.items.${featureKey}.desc`))
const detail = computed(() => t(`landing.features.items.${featureKey}.detail`))
// 配列ロケールは tm() で取得し各要素を rt() で解決する（t() は常に文字列を返すため Array.isArray が成立しない）
const featuresList = computed(() => {
  const raw: unknown = tm(`landing.features.items.${featureKey}.features`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})
const useCases = computed(() => {
  const raw: unknown = tm(`landing.features.items.${featureKey}.useCases`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})

useSeoMeta({
  title: () => `${title.value} - Mannschaft`,
  description: () => desc.value,
})
</script>

<template>
  <div class="min-h-screen bg-surface-50 dark:bg-surface-900">
    <div class="mx-auto max-w-4xl px-4 py-12">
      <!-- 戻るリンク -->
      <div class="mb-8">
        <NuxtLink
          to="/"
          class="inline-flex items-center gap-2 text-sm text-surface-500 transition-colors hover:text-primary"
        >
          {{ t('landing.features_detail.back_to_top') }}
        </NuxtLink>
      </div>

      <!-- ヒーロー -->
      <div class="mb-12 text-center">
        <div class="mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-2xl bg-primary/10">
          <i :class="[icons[featureKey], 'text-4xl text-primary']" />
        </div>
        <h1 class="mb-4 text-3xl font-bold text-surface-900 dark:text-white md:text-4xl">
          {{ title }}
        </h1>
        <p class="text-lg text-surface-500">
          {{ desc }}
        </p>
      </div>

      <!-- 詳細説明 -->
      <div class="mb-10 rounded-2xl bg-white p-8 shadow-sm dark:bg-surface-800">
        <p class="text-base leading-relaxed text-surface-700 dark:text-surface-300">
          {{ detail }}
        </p>
      </div>

      <div class="mb-10 grid gap-6 md:grid-cols-2">
        <!-- 主要機能リスト -->
        <div class="rounded-2xl bg-white p-8 shadow-sm dark:bg-surface-800">
          <h2 class="mb-6 text-xl font-bold text-surface-900 dark:text-white">
            {{ t('landing.features_detail.key_features') }}
          </h2>
          <ul class="space-y-3">
            <li
              v-for="(feature, idx) in featuresList"
              :key="idx"
              class="flex items-start gap-3"
            >
              <i class="pi pi-check-circle mt-0.5 shrink-0 text-primary" />
              <span class="text-surface-700 dark:text-surface-300">{{ feature }}</span>
            </li>
          </ul>
        </div>

        <!-- 利用シーン -->
        <div class="rounded-2xl bg-white p-8 shadow-sm dark:bg-surface-800">
          <h2 class="mb-6 text-xl font-bold text-surface-900 dark:text-white">
            {{ t('landing.features_detail.use_cases') }}
          </h2>
          <ul class="space-y-3">
            <li
              v-for="(useCase, idx) in useCases"
              :key="idx"
              class="flex items-start gap-3"
            >
              <i class="pi pi-arrow-right mt-0.5 shrink-0 text-primary" />
              <span class="text-surface-700 dark:text-surface-300">{{ useCase }}</span>
            </li>
          </ul>
        </div>
      </div>

      <!-- CTAボタン -->
      <div class="text-center">
        <NuxtLink
          to="/register"
          class="inline-flex items-center gap-2 rounded-xl bg-primary px-8 py-4 text-lg font-semibold text-white shadow-lg transition-all duration-200 hover:-translate-y-0.5 hover:bg-primary-600 hover:shadow-xl"
        >
          {{ t('landing.features_detail.cta_register') }}
          <i class="pi pi-arrow-right" />
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
