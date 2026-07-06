<script setup lang="ts">
import QRCode from 'qrcode'

/**
 * 全 QR コード描画の共通口となる共有コンポーネント。
 *
 * <p>これまで各画面（会員証共有・店主用チェックイン・バーコード表示 等）が
 * それぞれ `qrcode` ライブラリを直接 import してコピペで描画していたが、
 * 本コンポーネントに一本化することで実装の重複と描画方式のブレを無くす。</p>
 *
 * <p>中央にブランドラベル（既定 "Mannschaft"）を重ね描きできる。QR コードは
 * 誤り訂正レベル（Error Correction Level）が高いほどコード内に欠損があっても
 * 復元可能なため、中央をラベルで覆っても正しくスキャンできるよう
 * 既定値を最高レベルの `H`（約30%まで復元可能）にしている。</p>
 */
const props = withDefaults(
  defineProps<{
    /** 符号化する文字列（必須） */
    value: string
    /** 描画サイズ（px） */
    size?: number
    /** 中央に重ねるブランドラベル。空文字なら非表示 */
    centerLabel?: string
    /** 誤り訂正レベル。中央ラベルで一部を覆うため既定は H */
    errorCorrectionLevel?: 'L' | 'M' | 'Q' | 'H'
    /** QR コード外周の余白モジュール数 */
    margin?: number
  }>(),
  {
    size: 320,
    centerLabel: 'Mannschaft',
    errorCorrectionLevel: 'H',
    margin: 1,
  },
)

const emit = defineEmits<{ error: [message: string] }>()

const qrSvg = ref<string>('')

async function renderQr() {
  if (!props.value) {
    qrSvg.value = ''
    return
  }
  try {
    qrSvg.value = await QRCode.toString(props.value, {
      type: 'svg',
      width: props.size,
      margin: props.margin,
      errorCorrectionLevel: props.errorCorrectionLevel,
    })
  }
  catch (e) {
    console.error('[qr/QrCodeImage] QR コード描画に失敗', e)
    qrSvg.value = ''
    emit('error', String(e))
  }
}

onMounted(() => {
  renderQr()
})

watch(
  () => [props.value, props.size, props.errorCorrectionLevel, props.margin],
  () => {
    renderQr()
  },
  { immediate: false },
)
</script>

<template>
  <div class="qr-code-image">
    <!-- qrcode ライブラリが返す自前生成の SVG マークアップを描画。
         ユーザー入力ではなく信頼できるソースのため XSS リスクは無し。 -->
    <!-- eslint-disable vue/no-v-html -->
    <div class="qr-code-image__svg" v-html="qrSvg" />
    <!-- eslint-enable vue/no-v-html -->

    <span v-if="centerLabel" class="qr-code-image__label">{{ centerLabel }}</span>
  </div>
</template>

<style scoped>
.qr-code-image {
  position: relative;
  display: inline-block;
  line-height: 0;
}
.qr-code-image__svg :deep(svg) {
  display: block;
  width: 100%;
  height: auto;
}
.qr-code-image__label {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  background: #fff;
  color: #111827;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.01em;
  white-space: nowrap;
  max-width: 24%;
  overflow: hidden;
  text-overflow: ellipsis;
  /* 白フチで周囲モジュールと分離しスキャン性を確保 */
  box-shadow: 0 0 0 3px #fff;
  pointer-events: none;
  user-select: none;
}
</style>
