<script setup lang="ts">
/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorModal C 案ハイブリッド分割。
 *
 * # 責務
 *  - URL カード型のフィールドを描画する:
 *      url (必須) / title (任意) / userNote (任意)
 *
 * # 親子関係
 *  - 親: `CardEditorModal.vue`（provide で context を共有）
 *
 * # inject する context フィールド
 *  - `url`      : リンク先 URL（必須）
 *  - `title`    : 表示用タイトル（任意）
 *  - `userNote` : 自由記述メモ
 *  - `errors`   : バリデーションエラー（`errors.url` を表示）
 */
import { useCardEditorContext } from './useCardEditorContext'

const { t } = useI18n()
const { url, title, userNote, errors } = useCardEditorContext()
</script>

<template>
  <div class="flex flex-col gap-1">
    <label for="cardEditorUrl" class="text-sm font-medium">
      {{ t('corkboard.modal.url') }}
    </label>
    <InputText
      id="cardEditorUrl"
      v-model="url"
      type="url"
      :placeholder="t('corkboard.modal.urlPlaceholder')"
      class="w-full"
      data-testid="card-editor-url-input"
    />
    <small v-if="errors.url" class="text-red-500">{{ errors.url }}</small>
  </div>
  <div class="flex flex-col gap-1">
    <label for="cardEditorUrlTitle" class="text-sm font-medium">
      {{ t('corkboard.modal.titleOptional') }}
    </label>
    <InputText
      id="cardEditorUrlTitle"
      v-model="title"
      class="w-full"
      data-testid="card-editor-title-input"
    />
  </div>
  <div class="flex flex-col gap-1">
    <label for="cardEditorUrlNote" class="text-sm font-medium">
      {{ t('corkboard.modal.userNote') }}
    </label>
    <Textarea
      id="cardEditorUrlNote"
      v-model="userNote"
      :placeholder="t('corkboard.modal.userNotePlaceholder')"
      rows="2"
      auto-resize
      class="w-full"
    />
  </div>
</template>
