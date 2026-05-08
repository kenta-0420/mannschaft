<script setup lang="ts">
/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorModal C 案ハイブリッド分割。
 *
 * # 責務
 *  - SECTION_HEADER カード型のフィールドを描画する:
 *      title (必須)
 *
 * # 親子関係
 *  - 親: `CardEditorModal.vue`（provide で context を共有）
 *
 * # inject する context フィールド
 *  - `title`  : セクション見出しのタイトル（必須）
 *  - `errors` : バリデーションエラー（`errors.title` を表示）
 */
import { useCardEditorContext } from './useCardEditorContext'

const { t } = useI18n()
const { title, errors } = useCardEditorContext()
</script>

<template>
  <div class="flex flex-col gap-1">
    <label for="cardEditorSectionTitle" class="text-sm font-medium">
      {{ t('corkboard.modal.title') }}
    </label>
    <InputText
      id="cardEditorSectionTitle"
      v-model="title"
      class="w-full"
      data-testid="card-editor-title-input"
    />
    <small class="text-xs text-surface-500">
      {{ t('corkboard.modal.titleSectionHint') }}
    </small>
    <small v-if="errors.title" class="text-red-500">{{ errors.title }}</small>
  </div>
</template>
