<script setup lang="ts">
const { t } = useI18n()
const route = useRoute()

const slugs = ['sports', 'community', 'business', 'education']
const icons = [
  'pi pi-flag',
  'pi pi-home',
  'pi pi-building',
  'pi pi-graduation-cap',
]
const iconColors = [
  'text-blue-500',
  'text-green-500',
  'text-purple-500',
  'text-orange-500',
]
const iconBgs = [
  'bg-blue-100 dark:bg-blue-950',
  'bg-green-100 dark:bg-green-950',
  'bg-purple-100 dark:bg-purple-950',
  'bg-orange-100 dark:bg-orange-950',
]

const slug = route.params.slug as string
const caseKey = slugs.indexOf(slug)

if (caseKey === -1) {
  throw createError({ statusCode: 404, statusMessage: 'Use case not found' })
}

definePageMeta({
  layout: 'landing',
  middleware: 'guest',
})

const title = computed(() => t(`landing.useCases.items.${caseKey}.title`))
const desc = computed(() => t(`landing.useCases.items.${caseKey}.desc`))
const detail = computed(() => t(`landing.useCases.items.${caseKey}.detail`))
const benefits = computed(() => {
  const raw = t(`landing.useCases.items.${caseKey}.benefits`)
  if (Array.isArray(raw)) return raw
  return []
})
const featuresUsed = computed(() => {
  const raw = t(`landing.useCases.items.${caseKey}.features_used`)
  if (Array.isArray(raw)) return raw
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
          {{ t('landing.use_cases_detail.back_to_top') }}
        </NuxtLink>
      </div>

      <!-- ヒーロー -->
      <div class="mb-12 text-center">
        <div :class="['mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-2xl', iconBgs[caseKey]]">
          <i :class="[icons[caseKey], 'text-4xl', iconColors[caseKey]]" />
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
        <!-- このシーンで使える機能 -->
        <div class="rounded-2xl bg-white p-8 shadow-sm dark:bg-surface-800">
          <h2 class="mb-6 text-xl font-bold text-surface-900 dark:text-white">
            {{ t('landing.use_cases_detail.features_used') }}
          </h2>
          <div class="flex flex-wrap gap-2">
            <span
              v-for="(feature, idx) in featuresUsed"
              :key="idx"
              :class="['rounded-full px-4 py-1.5 text-sm font-medium', iconBgs[caseKey], iconColors[caseKey]]"
            >
              {{ feature }}
            </span>
          </div>
        </div>

        <!-- メリット -->
        <div class="rounded-2xl bg-white p-8 shadow-sm dark:bg-surface-800">
          <h2 class="mb-6 text-xl font-bold text-surface-900 dark:text-white">
            {{ t('landing.use_cases_detail.benefits') }}
          </h2>
          <ul class="space-y-3">
            <li
              v-for="(benefit, idx) in benefits"
              :key="idx"
              class="flex items-start gap-3"
            >
              <i :class="['pi pi-check-circle mt-0.5 shrink-0', iconColors[caseKey]]" />
              <span class="text-surface-700 dark:text-surface-300">{{ benefit }}</span>
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
          {{ t('landing.use_cases_detail.cta_register') }}
          <i class="pi pi-arrow-right" />
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
