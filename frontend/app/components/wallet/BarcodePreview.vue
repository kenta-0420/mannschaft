<script setup lang="ts">
import JsBarcode from 'jsbarcode'
import QRCode from 'qrcode'
import type { BarcodeFormat } from '~/types/pointCard'

/**
 * F18 ウォレット — バーコード描画コンポーネント。
 *
 * <p>設計書 §8.2 / §8.4 / §8.5 に基づき、QR は qrcode、1D は jsbarcode、
 * NONE はテキスト表示とフォールバックする。バーコード下にカード番号を大きく
 * 表示（設計書 §6.4 / §8.4 「`last4` 強調 + 全桁 1 段下に小さく表示」）する。</p>
 *
 * <p>props.value が空・無効・形式不一致で描画失敗した場合は赤エラーテキストを出す
 * （対処療法で握りつぶさず、ユーザーに明示する）。</p>
 *
 * @prop value バーコード値（カード番号文字列）
 * @prop format バーコード形式（'QR' / 'CODE128' / ... / 'NONE'）
 * @prop size 'normal' = 通常表示 / 'large' = 提示モード相当の大きいサイズ
 */

const props = withDefaults(
  defineProps<{
    value: string
    format: BarcodeFormat
    size?: 'normal' | 'large'
  }>(),
  {
    size: 'normal',
  },
)

const { t } = useI18n()

const canvasRef = ref<HTMLCanvasElement | null>(null)
const renderError = ref(false)

// jsbarcode が受け付ける format コード（type の値 → jsbarcode 用コード）。
// JAN13 は EAN13 と同一規格のため EAN13 で描画する。
const JSBARCODE_FORMAT_MAP: Record<string, string> = {
  CODE128: 'CODE128',
  CODE39: 'CODE39',
  EAN13: 'EAN13',
  EAN8: 'EAN8',
  JAN13: 'EAN13',
  ITF: 'ITF',
  PDF417: 'PDF417',
}

/** value の表示用整形: 4 桁ごとに半角スペース区切り（QR・PDF417 のような長い値は raw 表示） */
const formattedValue = computed(() => {
  if (!props.value) return ''
  if (props.format === 'QR' || props.format === 'PDF417') return props.value
  // 数字主体の 1D バーコードは 4 桁区切りにすると視認性が高い
  return props.value.replace(/(.{4})/g, '$1 ').trim()
})

async function render() {
  renderError.value = false
  if (!canvasRef.value) return
  if (props.format === 'NONE' || !props.value) {
    // NONE: canvas に何も描かない（テキストフォールバック側で表示）
    const ctx = canvasRef.value.getContext('2d')
    ctx?.clearRect(0, 0, canvasRef.value.width, canvasRef.value.height)
    return
  }

  try {
    if (props.format === 'QR') {
      const qrSize = props.size === 'large' ? 320 : 220
      await QRCode.toCanvas(canvasRef.value, props.value, {
        width: qrSize,
        margin: 1,
        errorCorrectionLevel: 'M',
      })
      return
    }

    const jsbarcodeFmt = JSBARCODE_FORMAT_MAP[props.format]
    if (!jsbarcodeFmt) {
      renderError.value = true
      return
    }
    JsBarcode(canvasRef.value, props.value, {
      format: jsbarcodeFmt,
      width: props.size === 'large' ? 3 : 2,
      height: props.size === 'large' ? 120 : 80,
      displayValue: false, // 自前で下に大きく出すので false
      margin: 8,
    })
  }
  catch (e) {
    // jsbarcode / qrcode が値不正で例外を投げた場合
    console.warn('[BarcodePreview] render failed', e)
    renderError.value = true
  }
}

watch(
  () => [props.value, props.format, props.size],
  async () => {
    await nextTick()
    render()
  },
)

onMounted(() => {
  // 初回描画
  nextTick(render)
})
</script>

<template>
  <div
    class="barcode-preview"
    :class="[`barcode-preview--${size}`]"
  >
    <!-- format === NONE の場合は専用メッセージのみ -->
    <template v-if="format === 'NONE'">
      <div class="barcode-preview__no-barcode" :aria-label="t('wallet.barcode.no_barcode_label')">
        <span class="barcode-preview__no-barcode-icon" aria-hidden="true">🏷️</span>
        <span class="barcode-preview__no-barcode-text">{{ t('wallet.barcode.no_barcode_label') }}</span>
      </div>
      <p v-if="value" class="barcode-preview__value-text">
        {{ value }}
      </p>
    </template>

    <template v-else>
      <canvas
        ref="canvasRef"
        class="barcode-preview__canvas"
        :aria-label="value"
      />
      <p v-if="renderError" class="barcode-preview__error" role="alert">
        {{ t('wallet.barcode.render_failed') }}
      </p>
      <p
        v-else-if="value"
        class="barcode-preview__value-text"
        :class="{ 'barcode-preview__value-text--large': size === 'large' }"
      >
        {{ formattedValue }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.barcode-preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem;
  background: #fff;
  border-radius: 0.75rem;
  border: 1px solid var(--p-surface-200, #e5e7eb);
}
.barcode-preview--large {
  padding: 1.5rem;
}
.barcode-preview__canvas {
  display: block;
  max-width: 100%;
  height: auto;
}
.barcode-preview__value-text {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 1rem;
  letter-spacing: 0.1em;
  margin: 0;
  color: var(--p-text-color, #111827);
  word-break: break-all;
  text-align: center;
}
.barcode-preview__value-text--large {
  font-size: 1.75rem;
  font-weight: 600;
  letter-spacing: 0.15em;
}
.barcode-preview__no-barcode {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 2rem 1rem;
  color: var(--p-text-muted-color, #6b7280);
}
.barcode-preview__no-barcode-icon {
  font-size: 2.5rem;
}
.barcode-preview__no-barcode-text {
  font-size: 0.9375rem;
  font-weight: 600;
}
.barcode-preview__error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
  text-align: center;
}
</style>
