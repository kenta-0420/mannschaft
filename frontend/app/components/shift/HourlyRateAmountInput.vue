<script setup lang="ts">
/**
 * 時給の金額入力欄（CMP-260913-1250 / Codex 検分の是正）。
 *
 * <h2>値をクランプしない【重要】</h2>
 * `InputNumber` に `min` を置かない。`:min="1"` を置くと 0 を入力した時点で黙って ¥1 に
 * 丸められ、検証を素通りして「時給 1 円」が保存されてしまう（実機で再現した欠陥）。
 * 入力値はそのまま保持し、保存時に弾いて理由をここに表示する。
 *
 * <h2>aria-describedby は Pass Through で内側の input に渡す【重要】</h2>
 * PrimeVue の `InputNumber` は `inheritAttrs: false` であり、未定義の属性は内側の
 * `<input>` ではなくルートの `<span>` に付く。素のままではスクリーンリーダーが
 * エラー説明を入力欄に関連付けられないため、`pt.pcInputText.root` 経由で渡す
 * （`pcInputText` が実際の `<input>` を描画する InputText。primevue 4.5.5 の
 * inputnumber/index.mjs で確認）。
 *
 * <h2>エラーの解除は input イベントで行う【重要】</h2>
 * `InputNumber` は打鍵中に `v-model` を更新せず、主に blur 時に書き戻す。
 * そのため `v-model` の watch では打鍵中にエラーを消せず、直している間も赤いまま残る。
 * 打鍵ごとに発火する `input` イベント（primevue の emits: ['input','focus','blur']）で
 * `clear-error` を上げる。
 */
defineProps<{
  /** 入力中の時給。未入力は null。 */
  modelValue: number | null
  /** 表示中の検証エラー文言。null ならエラー無し。 */
  errorMessage: string | null
  /** エラー説明要素の id（`aria-describedby` と対で使う）。 */
  errorId: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  /** 利用者が値を直し始めたので、表示中のエラーを解除してよい。 */
  'clear-error': []
}>()

function onModelUpdate(value: number | null) {
  emit('update:modelValue', value)
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <InputNumber
      id="hourly-rate-input"
      :model-value="modelValue"
      mode="currency"
      currency="JPY"
      locale="ja-JP"
      class="w-full"
      :invalid="errorMessage !== null"
      :pt="{ pcInputText: { root: { 'aria-describedby': errorMessage !== null ? errorId : undefined } } }"
      data-testid="hourly-rate-input"
      @update:model-value="onModelUpdate"
      @input="emit('clear-error')"
    />
    <small
      v-if="errorMessage"
      :id="errorId"
      class="text-red-600 dark:text-red-400"
      data-testid="hourly-rate-error"
    >
      {{ errorMessage }}
    </small>
  </div>
</template>
