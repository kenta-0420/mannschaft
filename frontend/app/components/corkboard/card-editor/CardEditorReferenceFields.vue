<script setup lang="ts">
/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorModal C 案ハイブリッド分割。
 *
 * # 責務
 *  - REFERENCE カード型のフィールドを描画する:
 *      referenceType / referenceId / URL ペースト補助 / userNote
 *  - URL ペースト → 数値 ID 抽出補助欄（create 時のみ、`URL` 種別は除外）
 *  - edit モードでは referenceType / referenceId は不変なので disabled にする
 *
 * # 親子関係
 *  - 親: `CardEditorModal.vue`（provide で context を共有）
 *
 * # inject する context フィールド
 *  - `referenceType`             : 参照種別
 *  - `referenceTypeOptions`      : セレクタ表示用オプション（i18n 込み）
 *  - `referenceId`               : 参照対象 ID
 *  - `referenceUrlPaste`         : URL ペースト一時入力欄（送信対象外）
 *  - `referenceUrlPasteMessage`  : URL 抽出成否メッセージ
 *  - `applyReferenceUrlPaste()`  : ペースト URL から ID を抽出してフィールドへ反映
 *  - `userNote`                  : 自由記述メモ
 *  - `errors`                    : バリデーションエラー
 *  - `mode`                      : edit のとき referenceType/Id を disabled にする
 */
import { computed } from 'vue'
import type { CorkboardReferenceType } from '~/types/corkboard'
import {
  useCardEditorContext,
  useCardEditorMode,
} from './useCardEditorContext'

const { t } = useI18n()
const mode = useCardEditorMode()
const {
  referenceType,
  referenceTypeOptions,
  referenceId,
  referenceUrlPaste,
  referenceUrlPasteMessage,
  applyReferenceUrlPaste,
  userNote,
  errors,
} = useCardEditorContext()

/**
 * 参照種別ごとに「どこから ID を取るか」のヒント文言キーを返す。
 * 既存 i18n キー `corkboard.referenceHint.*` を再利用。
 */
const referenceHintKey = computed<string>(() => {
  const map: Record<CorkboardReferenceType, string> = {
    TIMELINE_POST: 'corkboard.referenceHint.timelinePost',
    BULLETIN_THREAD: 'corkboard.referenceHint.bulletinThread',
    BLOG_POST: 'corkboard.referenceHint.blogPost',
    CHAT_MESSAGE: 'corkboard.referenceHint.chatMessage',
    FILE: 'corkboard.referenceHint.file',
    TEAM: 'corkboard.referenceHint.team',
    ORGANIZATION: 'corkboard.referenceHint.organization',
    EVENT: 'corkboard.referenceHint.event',
    DOCUMENT: 'corkboard.referenceHint.document',
    URL: 'corkboard.referenceHint.url',
  }
  return map[referenceType.value] ?? 'corkboard.modal.referenceIdHint'
})
</script>

<template>
  <div class="flex flex-col gap-1">
    <label for="cardEditorRefType" class="text-sm font-medium">
      {{ t('corkboard.modal.referenceType') }}
    </label>
    <Select
      id="cardEditorRefType"
      v-model="referenceType"
      :options="referenceTypeOptions"
      option-label="label"
      option-value="value"
      class="w-full"
      :disabled="mode === 'edit'"
      data-testid="card-editor-reference-type-select"
    />
    <small v-if="errors.referenceType" class="text-red-500">
      {{ errors.referenceType }}
    </small>
  </div>
  <div class="flex flex-col gap-1">
    <label for="cardEditorRefId" class="text-sm font-medium">
      {{ t('corkboard.modal.referenceId') }}
    </label>
    <InputNumber
      id="cardEditorRefId"
      v-model="referenceId"
      :min="1"
      :use-grouping="false"
      class="w-full"
      :disabled="mode === 'edit'"
      data-testid="card-editor-reference-id-input"
    />
    <!-- F09.8 Phase G: 参照種別ごとのヒント -->
    <small class="text-xs text-surface-500">
      {{ t(referenceHintKey) }}
    </small>
    <small v-if="errors.referenceId" class="text-red-500">
      {{ errors.referenceId }}
    </small>
  </div>

  <!-- F09.8 Phase G: URL ペーストで ID を自動抽出する補助欄（create 時のみ） -->
  <div
    v-if="mode === 'create' && referenceType !== 'URL'"
    class="flex flex-col gap-1 rounded border border-dashed border-surface-300 p-2 dark:border-surface-700"
  >
    <label
      for="cardEditorRefUrlPaste"
      class="text-xs font-medium text-surface-600 dark:text-surface-300"
    >
      {{ t('corkboard.modal.referenceUrlPaste') }}
    </label>
    <div class="flex gap-2">
      <InputText
        id="cardEditorRefUrlPaste"
        v-model="referenceUrlPaste"
        type="url"
        :placeholder="t('corkboard.modal.referenceUrlPastePlaceholder')"
        class="flex-1"
        data-testid="card-editor-reference-url-paste-input"
        @keydown.enter.prevent="applyReferenceUrlPaste"
      />
      <Button
        :label="t('corkboard.modal.referenceUrlPaste')"
        icon="pi pi-arrow-right"
        size="small"
        severity="secondary"
        :disabled="!referenceUrlPaste.trim()"
        data-testid="card-editor-reference-url-paste-button"
        @click="applyReferenceUrlPaste"
      />
    </div>
    <small class="text-[11px] text-surface-500">
      {{ t('corkboard.modal.referenceIdPasteHint') }}
    </small>
    <small
      v-if="referenceUrlPasteMessage"
      :class="
        referenceUrlPasteMessage.kind === 'success'
          ? 'text-green-600 dark:text-green-400'
          : 'text-red-500'
      "
      class="text-[11px]"
      role="status"
      data-testid="card-editor-reference-url-paste-message"
    >
      {{ referenceUrlPasteMessage.text }}
    </small>
  </div>

  <div class="flex flex-col gap-1">
    <label for="cardEditorRefNote" class="text-sm font-medium">
      {{ t('corkboard.modal.userNote') }}
    </label>
    <Textarea
      id="cardEditorRefNote"
      v-model="userNote"
      :placeholder="t('corkboard.modal.userNotePlaceholder')"
      rows="3"
      auto-resize
      class="w-full"
    />
  </div>
</template>
