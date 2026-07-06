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

/**
 * 中央ブランドバッジの寸法を size に比例させるインラインスタイル。
 * scoped CSS で固定 px にすると小さい QR で比率が崩れるため、
 * フォント・パディング・縁取り幅・角丸・白ハローをすべて size 連動にする。
 * 例: size=320 → fontSize≈17px / border≈3px / radius≈10px。
 */
const badgeStyle = computed(() => {
  const s = props.size
  return {
    fontSize: `${Math.round(s * 0.052)}px`,
    padding: `${Math.round(s * 0.014)}px ${Math.round(s * 0.032)}px`,
    borderWidth: `${Math.max(2, Math.round(s * 0.009))}px`,
    borderRadius: `${Math.round(s * 0.03)}px`,
    boxShadow: `0 0 0 ${Math.max(3, Math.round(s * 0.013))}px #fff`,
  }
})
</script>

<template>
  <div class="qr-code-image">
    <!-- qrcode ライブラリが返す自前生成の SVG マークアップを描画。
         ユーザー入力ではなく信頼できるソースのため XSS リスクは無し。 -->
    <!-- eslint-disable vue/no-v-html -->
    <div class="qr-code-image__svg" v-html="qrSvg" />
    <!-- eslint-enable vue/no-v-html -->

    <span
      v-if="centerLabel"
      class="qr-code-image__badge"
      :style="badgeStyle"
    >{{ centerLabel }}</span>
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
/**
 * 中央ブランドバッジ。四角い縁取り（角丸）＋白背景＋太字で "Mannschaft" をフル表示。
 * ellipsis は使わずフル表示する（省略防止）。寸法は badgeStyle（インライン）で size 連動。
 * ブランド色の縁取り＋白ハローで周囲モジュールから分離しスキャン性を確保する。
 * 被覆は ~30% 想定でも ECL=H（~30% 復元）内に収まる。
 */
.qr-code-image__badge {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  color: #111827;
  border-style: solid;
  border-color: var(--p-primary-color, #4f46e5);
  font-weight: 800;
  letter-spacing: 0.01em;
  white-space: nowrap;
  max-width: 46%;
  pointer-events: none;
  user-select: none;
}
</style>
