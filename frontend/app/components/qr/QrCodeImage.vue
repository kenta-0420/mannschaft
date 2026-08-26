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
 * SVG 文字列から一辺の長さ（ユーザー単位）を取得する。
 * qrcode ライブラリの SVG は `viewBox="0 0 W W"` 形式（W=モジュール数+2×margin）。
 * viewBox が取得できない場合は width/height 属性にフォールバックする。
 * いずれも取得できなければ null（呼び出し側でバッジ注入を断念しフォールバックする）。
 */
function extractSvgUnitSize(svg: string): number | null {
  const patterns = [
    /viewBox="0\s+0\s+([\d.]+)\s+[\d.]+"/,
    /\swidth="([\d.]+)"/,
    /\sheight="([\d.]+)"/,
  ]
  for (const pattern of patterns) {
    const raw = svg.match(pattern)?.[1]
    if (raw === undefined) continue
    const n = Number.parseFloat(raw)
    if (!Number.isNaN(n) && n > 0) return n
  }
  return null
}

/** SVG テキストノードに埋め込む前に XML 特殊文字をエスケープする。 */
function escapeXmlText(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * エクスポート（ダウンロード等）用に、画面表示の中央ブランドバッジ（HTMLオーバーレイ）と
 * 同等の見た目を SVG ネイティブの `<rect>`＋`<text>` として rawSvg に埋め込んで返す。
 *
 * <p>画面表示側（テンプレートの `.qr-code-image__badge` span オーバーレイ）は本関数と
 * 完全に独立しており、一切変更しない。本関数はエクスポート専用の別経路。</p>
 *
 * <p>座標はすべて SVG の viewBox ユーザー単位で計算し、画面表示の badgeStyle の
 * 比率（fontSize=size*0.052 等）に合わせている。</p>
 */
function buildBadgedSvg(rawSvg: string): string {
  if (!rawSvg || !props.centerLabel) return rawSvg

  const w = extractSvgUnitSize(rawSvg)
  if (w === null) {
    console.warn('[qr/QrCodeImage] SVG の viewBox/width が取得できないため、エクスポート用バッジ埋め込みを断念しました（QR本体はそのまま出力します）')
    return rawSvg
  }

  const fontSize = w * 0.052
  const padX = w * 0.05
  const padY = w * 0.04
  const textW = fontSize * 0.6 * props.centerLabel.length
  const badgeW = Math.min(textW + padX * 2, w * 0.46)
  const badgeH = fontSize + padY * 2
  const cx = w / 2
  const cy = w / 2 + badgeH * 0.1
  const rx = w * 0.05
  const strokeW = Math.max(w * 0.009, w / 160)
  const rectX = cx - badgeW / 2
  const rectY = cy - badgeH / 2
  // テキストが badgeW をはみ出さないよう textLength で安全に収める（安全マージン込み）。
  const safeTextLength = Math.max(Math.min(textW, badgeW - padX * 1.2), 0)

  const round = (n: number): string => (Math.round(n * 1000) / 1000).toString()

  const badgeMarkup = [
    `<rect x="${round(rectX)}" y="${round(rectY)}" width="${round(badgeW)}" height="${round(badgeH)}" `
    + `rx="${round(rx)}" ry="${round(rx)}" fill="#ffffff" stroke="${props.frameColor}" stroke-width="${round(strokeW)}"/>`,
    `<text x="${round(cx)}" y="${round(cy)}" fill="${props.frameColor}" font-family="sans-serif" font-weight="700" `
    + `font-size="${round(fontSize)}" text-anchor="middle" dominant-baseline="central" `
    + `textLength="${round(safeTextLength)}" lengthAdjust="spacingAndGlyphs">${escapeXmlText(props.centerLabel)}</text>`,
  ].join('')

  const closeTagIndex = rawSvg.lastIndexOf('</svg>')
  if (closeTagIndex === -1) {
    console.warn('[qr/QrCodeImage] </svg> が見つからないため、エクスポート用バッジ埋め込みを断念しました（QR本体はそのまま出力します）')
    return rawSvg
  }
  return rawSvg.slice(0, closeTagIndex) + badgeMarkup + rawSvg.slice(closeTagIndex)
}

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

/**
 * 親コンポーネントが現在描画中の QR SVG マークアップを取得できるよう公開する。
 * 用途: SVG ダウンロード等。画面表示の中央バッジは HTML オーバーレイのため生の
 * qrSvg には含まれないが、ここでは {@link buildBadgedSvg} により
 * バッジ相当の `<rect>`＋`<text>` を埋め込んだ SVG を返す（centerLabel が空文字の場合は
 * 埋め込まず生 SVG のまま返す）。未描画時は空文字を返す。
 */
defineExpose({
  getSvg: (): string => buildBadgedSvg(qrSvg.value),
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
  /* 水平は中央維持。垂直は translateY=10%（中央から自身の高さ0.6個分下）。
     中央=translateY(-50%)、1個分=100% → 0.6個分下 = -50% + 60% = 10%。
     translateY(%) は要素自身の高さ基準なので size 非依存。 */
  transform: translate(-50%, 10%);
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
