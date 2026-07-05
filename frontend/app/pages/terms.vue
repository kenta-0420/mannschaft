<script setup lang="ts">
const { t, tm, rt } = useI18n()

// 公開情報ページのため middleware は付けない
// （guest だと認証済みユーザーが /dashboard に飛ばされ、規約を読めなくなる）
definePageMeta({
  layout: 'landing',
})

useSeoMeta({
  title: () => t('landing.legal.terms.title') + ' - Mannschaft',
  description: () => t('landing.legal.terms.seo_description'),
})

interface TermsSection {
  title: string
  content: string[]
}

// 条文は sections 配列（title＋段落配列）。配列・ネストは tm() + rt() で解決する（t() は文字列しか返せない）
const sections = computed<TermsSection[]>(() => {
  const raw: unknown = tm('landing.legal.terms.sections')
  if (!Array.isArray(raw)) return []
  return raw.map((sec) => {
    const s = sec as { title?: unknown; content?: unknown }
    return {
      title: s.title === undefined ? '' : rt(s.title as Parameters<typeof rt>[0]),
      content: Array.isArray(s.content) ? s.content.map((c) => rt(c as Parameters<typeof rt>[0])) : [],
    }
  })
})
</script>

<template>
  <div class="min-h-screen bg-surface-50 dark:bg-surface-900">
    <div class="mx-auto max-w-3xl px-4 py-12">
      <!-- 戻るリンク -->
      <div class="mb-8">
        <NuxtLink
          to="/"
          class="inline-flex items-center gap-2 text-sm text-surface-500 transition-colors hover:text-primary"
        >
          {{ t('landing.features_detail.back_to_top') }}
        </NuxtLink>
      </div>

      <div class="rounded-2xl bg-white p-6 shadow-sm dark:bg-surface-800 md:p-10">
        <h1 class="mb-2 text-3xl font-bold text-surface-900 dark:text-white">
          {{ t('landing.legal.terms.title') }}
        </h1>
        <p class="mb-2 text-sm text-surface-400">
          {{ t('landing.legal.terms.last_updated') }}: {{ t('landing.legal.terms.effective_date') }}
        </p>
        <!-- 言語条項の注記（日本語版が正文） -->
        <p class="mb-10 text-xs text-surface-400">
          {{ t('landing.legal.terms.language_note') }}
        </p>

        <div class="space-y-8 text-left">
          <section
            v-for="(section, idx) in sections"
            :key="idx"
          >
            <h2 class="mb-3 text-lg font-bold text-surface-900 dark:text-white">
              {{ section.title }}
            </h2>
            <p
              v-for="(paragraph, pIdx) in section.content"
              :key="pIdx"
              class="mb-3 leading-relaxed text-surface-600 last:mb-0 dark:text-surface-300"
            >
              {{ paragraph }}
            </p>
          </section>
        </div>
      </div>

      <!-- リンク -->
      <div class="mt-8 text-center">
        <NuxtLink
          to="/privacy"
          class="text-sm text-primary hover:underline"
        >
          {{ t('landing.layout.footer_privacy') }} →
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
