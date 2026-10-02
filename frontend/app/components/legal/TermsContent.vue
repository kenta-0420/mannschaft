<script setup lang="ts">
/** 利用規約の本文。ページ（terms.vue）とモーダル（TermsModal.vue）の両方から利用する。 */
const { t, tm, rt } = useI18n()

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
      content: Array.isArray(s.content) ? s.content.map(c => rt(c as Parameters<typeof rt>[0])) : [],
    }
  })
})
</script>

<template>
  <div>
    <h1 class="mb-2 text-3xl font-bold text-surface-900 dark:text-white">
      {{ t('landing.legal.terms.title') }}
    </h1>
    <p class="mb-1 text-sm text-surface-400">
      {{ t('landing.legal.terms.version_notice') }}
    </p>
    <p class="mb-2 text-xs text-surface-400">
      {{ t('landing.legal.terms.enacted_label') }}: {{ t('landing.legal.terms.enacted_date') }}
      <span class="mx-1.5">·</span>
      {{ t('landing.legal.terms.revised_label') }}: {{ t('landing.legal.terms.revised_date') }}
    </p>
    <!-- 言語条項の注記（日本語版が正文） -->
    <p class="mb-8 text-xs text-surface-400">
      {{ t('landing.legal.terms.language_note') }}
    </p>

    <!-- 目次（条タイトルから生成・アンカーリンク） -->
    <nav
      :aria-label="t('landing.legal.terms.toc_label')"
      class="mb-10 rounded-xl bg-surface-50 p-4 dark:bg-surface-900"
    >
      <p class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('landing.legal.terms.toc_label') }}
      </p>
      <ol class="grid gap-1 text-sm sm:grid-cols-2">
        <li v-for="(section, idx) in sections" :key="idx">
          <a :href="`#terms-sec-${idx + 1}`" class="text-primary hover:underline">
            {{ section.title }}
          </a>
        </li>
      </ol>
    </nav>

    <div class="space-y-8 text-left">
      <section
        v-for="(section, idx) in sections"
        :id="`terms-sec-${idx + 1}`"
        :key="idx"
        class="scroll-mt-20"
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
</template>
