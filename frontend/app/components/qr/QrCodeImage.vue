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
    /**
     * QR 暗モジュールの色（HEX）。既定はブランド primary 緑の濃い版 #047857（primary-700 相当）。
     * ブランド primary の #10b981（primary-500）は白背景とのコントラスト比が 2.54:1 で
     * QR スキャンに不十分（3:1 未満）なため、同じ緑ランプで白コントラスト 5.48:1 を確保できる
     * #047857 を採用している。バッジ枠・文字は灰色（frameColor）で、QR 本体だけ緑にする。
     */
    moduleColor?: string
    /**
     * 中央バッジの枠線色＋文字色（HEX）。既定は灰色 #6b7280（gray-500、白背景コントラスト約4.6:1で可読）。
     * QR 本体の緑（moduleColor）とは独立。緑QR＋灰色バッジの組み合わせにする。
     */
    frameColor?: string
  }>(),
  {
    size: 320,
    centerLabel: 'Mannschaft',
    errorCorrectionLevel: 'H',
    margin: 1,
    moduleColor: '#047857',
    frameColor: '#6b7280',
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
      color: { dark: props.moduleColor, light: '#ffffff' },
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
  () => [props.value, props.size, props.errorCorrectionLevel, props.margin, props.moduleColor],
  () => {
    renderQr()
  },
  { immediate: false },
)

/**
 * 中央ブランドバッジの寸法を size に比例させるインラインスタイル。
 * scoped CSS で固定 px にすると小さい QR で比率が崩れるため、
 * フォント・パディング・縁取り幅・角丸・白ハローをすべて size 連動にする。
 * 例: size=320 → fontSize≈17px / padding≈13px 16px / border≈3px / radius≈16px。
 * 枠線色・文字色は frameColor（灰色）を適用する。
 */
const badgeStyle = computed(() => {
  const s = props.size
  return {
    fontSize: `${Math.round(s * 0.052)}px`,
    padding: `${Math.round(s * 0.04)}px ${Math.round(s * 0.05)}px`,
    borderWidth: `${Math.max(2, Math.round(s * 0.009))}px`,
    borderRadius: `${Math.round(s * 0.05)}px`,
    boxShadow: `0 0 0 ${Math.max(3, Math.round(s * 0.013))}px #fff`,
    borderColor: props.frameColor,
    color: props.frameColor,
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
 * 枠線色・文字色は badgeStyle 側で frameColor（灰色）を与える（緑QRから独立）。
 * 灰色枠＋白ハローで周囲の緑モジュールから分離しスキャン性を確保する。
 * 被覆は ~30% 想定でも ECL=H（~30% 復元）内に収まる。
 */
.qr-code-image__badge {
  position: absolute;
  top: 50%;
  left: 50%;
  /* 水平は中央維持。垂直は translateY=20%（中央から自身の高さ0.7個分下）。
     中央=translateY(-50%)、1個分=100% → 0.7個分下 = -50% + 70% = 20%。
     translateY(%) は要素自身の高さ基準なので size 非依存。 */
  transform: translate(-50%, 20%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border-style: solid;
  font-weight: 800;
  letter-spacing: 0.01em;
  white-space: nowrap;
  max-width: 46%;
  pointer-events: none;
  user-select: none;
}
</style>
