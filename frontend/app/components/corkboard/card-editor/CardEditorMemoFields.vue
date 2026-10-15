<script setup lang="ts">
/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorModal C 案ハイブリッド分割。
 *
 * # 責務
 *  - MEMO カード型のフィールドを描画する:
 *      title (任意) / body (必須) / userNote (任意)
 *
 * # 親子関係
 *  - 親: `CardEditorModal.vue`（provide で context を共有）
 *
 * # inject する context フィールド
 *  - `title`    : MEMO のタイトル（任意）
 *  - `body`     : MEMO の本文（必須）
 *  - `userNote` : 自由記述メモ
 *  - `errors`   : バリデーションエラー（`errors.body` を表示）
 */
import { useCardEditorContext } from './useCardEditorContext'

const { t } = useI18n()
const { title, body, userNote, errors } = useCardEditorContext()
</script>

<template>
  <div class="flex flex-col gap-1">
    <label for="cardEditorMemoTitle" class="text-sm font-medium">
      {{ t('corkboard.modal.titleOptional') }}
    </label>
    <InputText
      id="cardEditorMemoTitle"
      v-model="title"
      class="w-full"
      data-testid="card-editor-title-input"
    />
  </div>
  <div class="flex flex-col gap-1">
    <label for="cardEditorMemoBody" class="text-sm font-medium">
      {{ t('corkboard.modal.body') }}
    </label>
    <Textarea
      id="cardEditorMemoBody"
      v-model="body"
      :placeholder="t('corkboard.modal.bodyPlaceholder')"
      rows="4"
      auto-resize
      class="w-full"
      data-testid="card-editor-body-input"
    />
    <small v-if="errors.body" class="text-red-500">{{ errors.body }}</small>
  </div>
  <div class="flex flex-col gap-1">
    <label for="cardEditorMemoNote" class="text-sm font-medium">
      {{ t('corkboard.modal.userNote') }}
    </label>
    <Textarea
      id="cardEditorMemoNote"
      v-model="userNote"
      :placeholder="t('corkboard.modal.userNotePlaceholder')"
      rows="2"
      auto-resize
      class="w-full"
    />
  </div>
</template>
