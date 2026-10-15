<script setup lang="ts">
const { t, tm, rt } = useI18n()
const route = useRoute()

// 旧LP（/）由来の汎用4業種。後方互換のため残す（現行LPのフッター等からリンクされている）
const legacySlugs = ['sports', 'community', 'business', 'education']
const legacyIcons = [
  'pi pi-flag',
  'pi pi-home',
  'pi pi-building',
  'pi pi-graduation-cap',
]
const legacyIconColors = [
  'text-blue-500',
  'text-green-500',
  'text-purple-500',
  'text-orange-500',
]
const legacyIconBgs = [
  'bg-blue-100 dark:bg-blue-950',
  'bg-green-100 dark:bg-green-950',
  'bg-purple-100 dark:bg-purple-950',
  'bg-orange-100 dark:bg-orange-950',
]

// LP v2 の業種プリセット10種（slug → アイコン）
const v2Icons: Record<string, string> = {
  'sports-team': 'pi pi-flag',
  'clinic': 'pi pi-heart',
  'school': 'pi pi-graduation-cap',
  'alumni': 'pi pi-users',
  'salon': 'pi pi-star',
  'mansion': 'pi pi-building',
  'neighborhood': 'pi pi-home',
  'gym': 'pi pi-bolt',
  'restaurant': 'pi pi-shopping-bag',
  'circle': 'pi pi-sparkles',
}

const slug = route.params.slug as string
const caseKey = legacySlugs.indexOf(slug)
const isV2 = Object.prototype.hasOwnProperty.call(v2Icons, slug)

if (caseKey === -1 && !isV2) {
  throw createError({ statusCode: 404, statusMessage: 'Use case not found' })
}

// 公開情報ページのため middleware は付けない
// （guest だと認証済みユーザーが /dashboard に飛ばされ、LPからの段階開示導線が壊れる）
definePageMeta({
  layout: 'landing',
})

// ---- v2 業種ページ用 ----
const v2Key = slug.replace(/-/g, '_')
const v2Title = computed(() => t(`landing.v2.usecase_pages.${v2Key}.title`))
const v2Problem = computed(() => t(`landing.v2.usecase_pages.${v2Key}.problem`))
// 配列ロケールは tm() で取得し各要素を rt() で解決する（t() は常に文字列を返す）
const v2Story = computed(() => {
  const raw: unknown = tm(`landing.v2.usecase_pages.${v2Key}.story`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})
const v2Chips = computed(() => {
  const raw: unknown = tm(`landing.v2.usecase_pages.${v2Key}.chips`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})

// ---- 旧4業種ページ用 ----
const title = computed(() => (isV2 ? v2Title.value : t(`landing.useCases.items.${caseKey}.title`)))
const desc = computed(() => (isV2 ? v2Problem.value : t(`landing.useCases.items.${caseKey}.desc`)))
const detail = computed(() => t(`landing.useCases.items.${caseKey}.detail`))
const benefits = computed(() => {
  const raw: unknown = tm(`landing.useCases.items.${caseKey}.benefits`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})
const featuresUsed = computed(() => {
  const raw: unknown = tm(`landing.useCases.items.${caseKey}.features_used`)
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
    <!-- ===== v2 業種別ページ ===== -->
    <div v-if="isV2" class="mx-auto max-w-3xl px-4 py-12">
      <!-- 戻るリンク -->
      <div class="mb-8">
        <NuxtLink
          to="/"
          class="inline-flex items-center gap-2 text-sm text-surface-500 transition-colors hover:text-primary"
        >
          {{ t('landing.v2.usecase_pages.common.back') }}
        </NuxtLink>
      </div>

      <!-- ヒーロー -->
      <div class="mb-10 text-center">
        <div class="mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-2xl bg-primary/10">
          <i :class="[v2Icons[slug], 'text-4xl text-primary']" />
        </div>
        <h1 class="mb-4 text-3xl font-bold text-surface-900 dark:text-white md:text-4xl">
          {{ v2Title }}
        </h1>
        <p class="mx-auto max-w-xl text-lg leading-relaxed text-surface-500">
          {{ v2Problem }}
        </p>
      </div>

      <!-- Mannschaftならこうなる（ストーリー） -->
      <div class="mb-8 rounded-2xl bg-white p-6 shadow-sm dark:bg-surface-800 md:p-8">
        <h2 class="mb-6 text-xl font-bold text-surface-900 dark:text-white">
          {{ t('landing.v2.usecase_pages.common.story_heading') }}
        </h2>
        <ol class="space-y-4">
          <li v-for="(item, idx) in v2Story" :key="idx" class="flex items-start gap-3">
            <span
              class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-bold text-primary"
            >
              {{ idx + 1 }}
            </span>
            <span class="leading-relaxed text-surface-700 dark:text-surface-300">{{ item }}</span>
          </li>
        </ol>
      </div>

      <!-- 使う機能 -->
      <div class="mb-10 rounded-2xl bg-white p-6 shadow-sm dark:bg-surface-800 md:p-8">
        <h2 class="mb-5 text-xl font-bold text-surface-900 dark:text-white">
          {{ t('landing.v2.usecase_pages.common.chips_heading') }}
        </h2>
        <div class="flex flex-wrap gap-2">
          <span
            v-for="(chip, idx) in v2Chips"
            :key="idx"
            class="rounded-full bg-primary/10 px-4 py-1.5 text-sm font-medium text-primary"
          >
            {{ chip }}
          </span>
        </div>
      </div>

      <!-- CTA -->
      <div class="text-center">
        <NuxtLink to="/register">
          <Button
            :label="t('landing.v2.usecase_pages.common.cta')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            size="large"
            class="px-10"
          />
        </NuxtLink>
      </div>
    </div>

    <!-- ===== 旧4業種ページ（後方互換） ===== -->
    <div v-else class="mx-auto max-w-4xl px-4 py-12">
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
        <div :class="['mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-2xl', legacyIconBgs[caseKey]]">
          <i :class="[legacyIcons[caseKey], 'text-4xl', legacyIconColors[caseKey]]" />
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
              :class="['rounded-full px-4 py-1.5 text-sm font-medium', legacyIconBgs[caseKey], legacyIconColors[caseKey]]"
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
              <i :class="['pi pi-check-circle mt-0.5 shrink-0', legacyIconColors[caseKey]]" />
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
