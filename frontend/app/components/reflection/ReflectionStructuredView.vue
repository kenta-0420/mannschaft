<script setup lang="ts">
/**
 * F06.5 構造化コンテンツの読み取り表示（§2.3）。
 *
 * 想起テストの開示後（original）や履歴表示で structured_content を整形表示する。
 * 値は JsonNode（生成型では opaque）由来のため、安全側で各フィールドを存在チェックして描画する。
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
    </div>

    <div v-if="content.free_note" class="rounded-lg bg-surface-100 p-2 dark:bg-surface-700">
      <p class="mb-1 text-xs text-surface-500">{{ t('reflection.entry.free_note_label') }}</p>
      <p class="whitespace-pre-wrap text-sm text-surface-600 dark:text-surface-300">{{ content.free_note }}</p>
    </div>
  </div>
</template>
