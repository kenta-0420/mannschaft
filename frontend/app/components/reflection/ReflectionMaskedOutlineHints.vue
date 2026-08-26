<script setup lang="ts">
/**
 * F06.5 OUTLINE 段階式マスク 足場ラダー表示コンポーネント（AC-91〜93）。
 *
 * マスク中エントリの OUTLINE 足場ヒントを level に応じて表示する。
 * - FULL  : mainTheme + 全 heading を表示（全文）
 * - PARTIAL: mainTheme + 各 heading を表示（BE が既に先頭3文字に切り詰め済み）＋頭文字ヒント注記
 * - HIDDEN : 何も表示しない
 *
 * TERM_CARD の cardQuiz とは別物。本コンポーネントは OUTLINE 専用。
 * sub_heading / detail / supplement は scaffold に存在しないため描画しない。
 */
import type { ReflectionMaskedOutlineScaffold } from '~/types/reflection'

const props = defineProps<{
  scaffold: ReflectionMaskedOutlineScaffold | null | undefined
}>()

const { t } = useI18n()

/** 表示すべき足場データがあるか（HIDDEN または空の場合は非表示） */
const shouldShow = computed(() => {
  if (!props.scaffold) return false
  if (props.scaffold.level === 'HIDDEN') return false
  const hasMainTheme = Boolean(props.scaffold.mainTheme)
  const hasSections = (props.scaffold.sections?.length ?? 0) > 0
  return hasMainTheme || hasSections
})

const isPartial = computed(() => props.scaffold?.level === 'PARTIAL')
</script>

<template>
  <div
    v-if="shouldShow"
    class="mt-3 rounded-lg border border-blue-200 bg-blue-50 p-3 dark:border-blue-700/50 dark:bg-blue-900/20"
    :aria-label="t('reflection.recall.outline_scaffold.aria_panel')"
  >
    <p class="mb-2 text-xs font-semibold uppercase tracking-wide text-blue-600 dark:text-blue-400">
      {{ t('reflection.recall.outline_scaffold.panel_heading') }}
    </p>

    <!-- PARTIAL 注記 -->
    <p v-if="isPartial" class="mb-2 text-xs text-blue-500 dark:text-blue-400">
      {{ t('reflection.recall.outline_scaffold.level_note_partial') }}
    </p>

    <!-- mainTheme -->
    <div v-if="scaffold?.mainTheme" class="mb-2">
      <span class="text-xs text-blue-500 dark:text-blue-400">
        {{ t('reflection.recall.outline_scaffold.main_theme_label') }}:
      </span>
      <span class="ml-1 text-sm font-medium text-blue-800 dark:text-blue-200">{{ scaffold.mainTheme }}</span>
    </div>

    <!-- 見出し一覧（チップ形式） -->
    <div v-if="scaffold?.sections?.length" class="flex flex-wrap gap-1.5">
      <span
        v-for="(section, index) in scaffold.sections"
        :key="index"
        class="inline-block rounded-md border border-blue-300 bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700 dark:border-blue-600 dark:bg-blue-800/50 dark:text-blue-300"
      >
        {{ section.heading }}
      </span>
    </div>
  </div>
</template>
