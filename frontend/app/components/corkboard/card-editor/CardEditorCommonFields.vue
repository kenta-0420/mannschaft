<script setup lang="ts">
/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorModal C 案ハイブリッド分割。
 *
 * # 責務
 *  - 全カード型で共通のフィールドを描画する:
 *      1. カード種別セレクタ（create のみ可変、edit は固定表示）
 *      2. カラーラベル（radiogroup）
 *      3. 位置 (positionX / positionY)
 *  - 中央に `<slot />` を持ち、親が型別の子コンポーネント
 *    （Memo/Url/Reference/SectionHeader）を差し込む。
 *
 * # 親子関係
 *  - 親: `CardEditorModal.vue` （provide で context を共有）
 *  - 子: `<slot />` で型別フィールドコンポーネントを受け取る
 *
 * # inject する context フィールド
 *  - `cardType`            : 現在のカード種別（v-model）
 *  - `cardTypeOptions`     : セレクタ表示用オプション配列（i18n 込み）
 *  - `colorLabel`          : 選択中の色ラベル
 *  - `colorOptions`        : 色オプション配列（swatch クラス込み）
 *  - `positionX`/`positionY`: 配置座標
 *  - `mode`                : create / edit 判定（type セレクタの可変／固定切替）
 */
import {
  useCardEditorContext,
  useCardEditorMode,
} from './useCardEditorContext'

const { t } = useI18n()
const mode = useCardEditorMode()
const {
  cardType,
  cardTypeOptions,
  colorLabel,
  colorOptions,
  positionX,
  positionY,
} = useCardEditorContext()
</script>

<template>
  <!-- カード種別（create のみ可変、edit では固定表示） -->
  <div class="flex flex-col gap-1">
    <label for="cardEditorType" class="text-sm font-medium">
      {{ t('corkboard.modal.cardType') }}
    </label>
    <Select
      v-if="mode === 'create'"
      id="cardEditorType"
      v-model="cardType"
      :options="cardTypeOptions"
      option-label="label"
      option-value="value"
      class="w-full"
      data-testid="card-editor-card-type-select"
    />
    <span
      v-else
      class="inline-flex items-center gap-2 rounded border border-surface-200 bg-surface-100 px-3 py-2 text-sm dark:border-surface-700 dark:bg-surface-800"
    >
      {{ cardTypeOptions.find((o) => o.value === cardType)?.label ?? cardType }}
    </span>
  </div>

  <!-- 型別フィールド（親から差し込まれる） -->
  <slot />

  <!-- カラーラベル -->
  <div class="flex flex-col gap-1">
    <span class="text-sm font-medium">{{ t('corkboard.modal.colorLabel') }}</span>
    <div role="radiogroup" :aria-label="t('corkboard.modal.colorLabel')" class="flex flex-wrap gap-2">
      <button
        v-for="opt in colorOptions"
        :key="opt.value"
        type="button"
        role="radio"
        :aria-checked="colorLabel === opt.value"
        :aria-label="opt.label"
        :data-testid="`card-editor-color-label-${opt.value}`"
        class="flex h-8 w-8 items-center justify-center rounded-full border-2 transition-all"
        :class="[
          opt.swatch,
          colorLabel === opt.value
            ? 'border-primary scale-110'
            : 'border-surface-300 dark:border-surface-600 hover:scale-105',
        ]"
        @click="colorLabel = opt.value"
      >
        <i
          v-if="colorLabel === opt.value"
          class="pi pi-check text-[10px] text-surface-900 dark:text-surface-50"
          aria-hidden="true"
        />
      </button>
    </div>
  </div>

  <!-- 位置 -->
  <fieldset class="flex flex-col gap-1">
    <legend class="text-sm font-medium">{{ t('corkboard.modal.position') }}</legend>
    <div class="grid grid-cols-2 gap-2">
      <div class="flex flex-col gap-1">
        <label for="cardEditorPosX" class="text-xs text-surface-500">
          {{ t('corkboard.modal.positionX') }}
        </label>
        <InputNumber
          id="cardEditorPosX"
          v-model="positionX"
          :min="0"
          :use-grouping="false"
          class="w-full"
        />
      </div>
      <div class="flex flex-col gap-1">
        <label for="cardEditorPosY" class="text-xs text-surface-500">
          {{ t('corkboard.modal.positionY') }}
        </label>
        <InputNumber
          id="cardEditorPosY"
          v-model="positionY"
          :min="0"
          :use-grouping="false"
          class="w-full"
        />
      </div>
    </div>
  </fieldset>
</template>
