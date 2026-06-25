<script setup lang="ts">
/**
 * F06.5 構造化コンテンツの読み取り表示（§2.3・Phase 4）。
 *
 * 想起テストの開示後（original）や履歴表示で structured_content を整形表示する。
 * Phase 4 追加: TERM_CARD section の語句↔意味カード表示。
 */
import type { ReflectionStructuredContent } from '~/types/reflection'

defineProps<{
  content: ReflectionStructuredContent
}>()

const { t } = useI18n()
</script>

<template>
  <div class="space-y-3">
    <h4 v-if="content.main_theme" class="text-base font-bold">
      {{ content.main_theme }}
    </h4>

    <div
      v-for="(section, si) in content.sections ?? []"
      :key="si"
      class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
    >
      <p v-if="section.heading" class="mb-2 font-semibold">{{ section.heading }}</p>

      <!-- OUTLINE: 小見出し表示 -->
      <template v-if="section.type !== 'TERM_CARD'">
        <div
          v-for="(sub, subi) in section.subsections ?? []"
          :key="subi"
          class="mb-2 last:mb-0"
        >
          <p v-if="sub.sub_heading" class="text-sm font-medium text-surface-700 dark:text-surface-200">
            {{ sub.sub_heading }}
          </p>
          <p v-if="sub.detail" class="whitespace-pre-wrap text-sm text-surface-600 dark:text-surface-300">
            {{ sub.detail }}
          </p>
          <p v-if="sub.supplement" class="whitespace-pre-wrap text-xs text-surface-500">
            {{ sub.supplement }}
          </p>
        </div>
      </template>

      <!-- TERM_CARD: 語句↔意味カード表示 -->
      <template v-else>
        <div
          v-for="(card, ci) in section.cards ?? []"
          :key="ci"
          class="mb-2 flex gap-3 rounded bg-surface-50 px-3 py-2 text-sm dark:bg-surface-800"
        >
          <span class="font-medium text-surface-700 dark:text-surface-200">{{ card.term }}</span>
          <span class="text-surface-400">→</span>
          <span class="text-surface-600 dark:text-surface-300">{{ card.meaning }}</span>
        </div>
      </template>
    </div>

    <div v-if="content.free_note" class="rounded-lg bg-surface-100 p-2 dark:bg-surface-700">
      <p class="mb-1 text-xs text-surface-500">{{ t('reflection.entry.free_note_label') }}</p>
      <p class="whitespace-pre-wrap text-sm text-surface-600 dark:text-surface-300">{{ content.free_note }}</p>
    </div>
  </div>
</template>
