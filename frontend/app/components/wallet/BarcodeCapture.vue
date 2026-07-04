<script setup lang="ts">
import {
  BarcodeFormat as ZxingBarcodeFormat,
  BrowserMultiFormatReader,
  type IScannerControls,
} from '@zxing/browser'
import type { Result } from '@zxing/library'
import type { BarcodeFormat } from '~/types/pointCard'
import { guessBarcodeFormat } from '~/utils/barcodeFormatGuess'

/**
 * F18 ウォレット — バーコードキャプチャコンポーネント。
 *
 * <p>設計書 §7.2 / §8.2 / §8.3 に基づき、3 種の入力方法（カメラ / 画像 / 手入力）をタブで
 * 切り替えながらバーコード値と形式を取得する。`@zxing/browser` の
 * `BrowserMultiFormatReader` で QR / 1D 双方をデコードする。</p>
 *
 * <p>カメラ拒否・カメラ未検出・デコード失敗は対処療法ではなく明示エラーで表示し、
 * 「再試行」と「手入力に切替」を提示する。検出後は emit `detected` でカメラを即停止する。</p>
 *
 * @emit detected バーコード値と形式
 * @emit no-barcode カードがバーコードを持たない（NONE）に切替えるユーザー操作
 */

const emit = defineEmits<{
  detected: [value: string, format: BarcodeFormat]
  'no-barcode': []
}>()

const { t } = useI18n()

type Tab = 'camera' | 'image' | 'manual'
const activeTab = ref<Tab>('camera')

const videoRef = ref<HTMLVideoElement | null>(null)
const cameraActive = ref(false)
const cameraError = ref<'denied' | 'no-camera' | 'decode' | null>(null)

let reader: BrowserMultiFormatReader | null = null
let controls: IScannerControls | null = null

// 手入力タブの状態
const manualValue = ref('')
const manualFormat = ref<BarcodeFormat>('CODE128')

/**
 * ユーザーが形式セレクトを一度でも手動で操作したかを追跡するフラグ。
 * このフラグが true になると、バーコード値の桁数による自動推測を停止する。
 * ユーザーの明示的な選択を watch の自動上書きで上書きしないための意図的な設計。
 */
const userTouchedFormat = ref(false)

/** 形式セレクトが手動操作されたことをマーク */
function onFormatChange() {
  userTouchedFormat.value = true
}

/**
 * バーコード値からチェックディジット検証済みの形式を自動推測し、ユーザーが未操作の間だけセットする。
 *
 * <p>旧実装は桁数のみで EAN13/EAN8 を判定していたため、GS1 チェックディジットが
 * 不正な値（例: 会員カードの任意13桁番号）を EAN13 と誤推測し、
 * JsBarcode が例外を投げて「バーコードの描画に失敗しました」エラーを引き起こしていた。</p>
 *
 * <p>guessBarcodeFormat はチェックディジット検証を行い、不正値は CODE128 に
 * 安全フォールバックするため、描画失敗を原理的になくす。</p>
 */
watch(manualValue, (newVal) => {
  // ユーザーが一度でも Select を操作した場合は自動上書きしない
  if (userTouchedFormat.value) return

  manualFormat.value = guessBarcodeFormat(newVal.trim())
})

// 画像読込タブの状態
const imageError = ref(false)

// SUPPORTED_FORMATS を補足ラベル付きオブジェクト配列として computed 化する。
// i18n の t() を使うことで多言語対応し、value（enum 文字列）を保持したまま
// optionLabel に日本語補足付きのラベルを表示できる。
const supportedFormatOptions = computed<{ label: string, value: BarcodeFormat }[]>(() => {
  const codes: BarcodeFormat[] = [
    'CODE128',
    'CODE39',
    'EAN13',
    'EAN8',
    'JAN13',
    'QR',
    'PDF417',
    'ITF',
  ]
  return codes.map((code) => ({
    label: t(`wallet.add.format_labels.${code}`),
    value: code,
  }))
})

/** zxing の BarcodeFormat enum → 本プロジェクトの BarcodeFormat 文字列に変換 */
function mapZxingFormat(fmt: ZxingBarcodeFormat): BarcodeFormat {
  switch (fmt) {
    case ZxingBarcodeFormat.CODE_128: return 'CODE128'
    case ZxingBarcodeFormat.CODE_39: return 'CODE39'
    case ZxingBarcodeFormat.EAN_13: return 'EAN13'
    case ZxingBarcodeFormat.EAN_8: return 'EAN8'
    case ZxingBarcodeFormat.QR_CODE: return 'QR'
    case ZxingBarcodeFormat.PDF_417: return 'PDF417'
    case ZxingBarcodeFormat.ITF: return 'ITF'
    // CODABAR / DATA_MATRIX 等は Phase 1 で未サポート → CODE128 にフォールバック
    default: return 'CODE128'
  }
}

function ensureReader(): BrowserMultiFormatReader {
  if (!reader) reader = new BrowserMultiFormatReader()
  return reader
}

async function startCamera() {
  if (!videoRef.value) return
  cameraError.value = null
  try {
    const r = ensureReader()
    controls = await r.decodeFromVideoDevice(
      undefined,
      videoRef.value,
      (result: Result | undefined) => {
        if (!result) return
        const value = result.getText()
        const format = mapZxingFormat(result.getBarcodeFormat())
        stopCamera()
        emit('detected', value, format)
      },
    )
    cameraActive.value = true
  }
  catch (e) {
    cameraActive.value = false
    const name = (e as DOMException | Error | undefined)?.name ?? ''
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      cameraError.value = 'denied'
    }
    else if (name === 'NotFoundError' || name === 'OverconstrainedError') {
      cameraError.value = 'no-camera'
    }
    else {
      cameraError.value = 'decode'
    }
    console.warn('[BarcodeCapture] camera start failed', e)
  }
}

function stopCamera() {
  if (controls) {
    controls.stop()
    controls = null
  }
  cameraActive.value = false
}

async function onImagePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  imageError.value = false

  try {
    const url = URL.createObjectURL(file)
    try {
      const r = ensureReader()
      const result = await r.decodeFromImageUrl(url)
      const value = result.getText()
      const format = mapZxingFormat(result.getBarcodeFormat())
      emit('detected', value, format)
    }
    finally {
      URL.revokeObjectURL(url)
    }
  }
  catch (e) {
    console.warn('[BarcodeCapture] image decode failed', e)
    imageError.value = true
  }
  finally {
    input.value = '' // 同じファイルを連続で選んでも change が再発火するようリセット
  }
}

function submitManual() {
  const value = manualValue.value.trim()
  if (!value) return
  emit('detected', value, manualFormat.value)
}

function emitNoBarcode() {
  stopCamera()
  emit('no-barcode')
}

function switchTab(tab: Tab) {
  if (tab === activeTab.value) return
  // タブ離脱時にカメラを必ず止める（リソース解放）
  if (activeTab.value === 'camera') stopCamera()
  activeTab.value = tab
}

onBeforeUnmount(stopCamera)
</script>

<template>
  <div class="barcode-capture">
    <!-- タブ切り替え -->
    <div class="barcode-capture__tabs" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'camera'"
        class="barcode-capture__tab"
        :class="{ 'barcode-capture__tab--active': activeTab === 'camera' }"
        @click="switchTab('camera')"
      >
        {{ t('wallet.scan.tab_camera') }}
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'image'"
        class="barcode-capture__tab"
        :class="{ 'barcode-capture__tab--active': activeTab === 'image' }"
        @click="switchTab('image')"
      >
        {{ t('wallet.scan.tab_image') }}
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'manual'"
        class="barcode-capture__tab"
        :class="{ 'barcode-capture__tab--active': activeTab === 'manual' }"
        @click="switchTab('manual')"
      >
        {{ t('wallet.scan.tab_manual') }}
      </button>
    </div>

    <!-- カメラタブ -->
    <section v-if="activeTab === 'camera'" class="barcode-capture__section">
      <p v-if="!cameraActive && !cameraError" class="barcode-capture__hint">
        {{ t('wallet.scan.permission_body') }}
      </p>

      <div class="barcode-capture__video-wrap">
        <video
          ref="videoRef"
          class="barcode-capture__video"
          playsinline
          muted
        />
        <div v-if="!cameraActive" class="barcode-capture__video-overlay">
          {{ t('wallet.scan.request_camera') }}
        </div>
      </div>

      <div class="barcode-capture__btn-row">
        <Button
          v-if="!cameraActive"
          :label="t('wallet.scan.start_camera')"
          @click="startCamera"
        />
        <Button
          v-else
          :label="t('wallet.scan.stop_camera')"
          severity="secondary"
          @click="stopCamera"
        />
      </div>

      <p v-if="cameraError === 'denied'" class="barcode-capture__error" role="alert">
        {{ t('wallet.scan.error_denied') }}
      </p>
      <p v-if="cameraError === 'no-camera'" class="barcode-capture__error" role="alert">
        {{ t('wallet.scan.error_no_camera') }}
      </p>
      <p v-if="cameraError === 'decode'" class="barcode-capture__error" role="alert">
        {{ t('wallet.scan.error_decode_failed') }}
      </p>
    </section>

    <!-- 画像タブ -->
    <section v-else-if="activeTab === 'image'" class="barcode-capture__section">
      <p class="barcode-capture__hint">
        {{ t('wallet.scan.image_hint') }}
      </p>
      <label class="barcode-capture__file-label">
        <input
          type="file"
          accept="image/*"
          class="barcode-capture__file-input"
          @change="onImagePicked"
        >
        <span class="barcode-capture__btn barcode-capture__btn--primary">
          {{ t('wallet.scan.image_pick') }}
        </span>
      </label>
      <p v-if="imageError" class="barcode-capture__error" role="alert">
        {{ t('wallet.scan.error_decode_failed') }}
      </p>
    </section>

    <!-- 手入力タブ -->
    <section v-else class="barcode-capture__section">
      <!-- カメラ・画像読み取りを推奨するヒント。手入力は最終手段であることを案内。 -->
      <p class="barcode-capture__hint">
        {{ t('wallet.scan.manual_recommend_scan') }}
      </p>
      <div class="barcode-capture__field">
        <label class="barcode-capture__label" for="bc-manual-value">
          {{ t('wallet.add.manual_input') }}
        </label>
        <InputText
          id="bc-manual-value"
          v-model="manualValue"
          class="w-full"
          :placeholder="t('wallet.add.manual_input_placeholder')"
          autocomplete="off"
          inputmode="numeric"
        />
      </div>
      <div class="barcode-capture__field">
        <label class="barcode-capture__label" for="bc-manual-format">
          {{ t('wallet.add.manual_format') }}
        </label>
        <!-- optionLabel/optionValue を指定して補足ラベル付き選択肢を表示。
             v-model="manualFormat" は BarcodeFormat 文字列（value）を保持。 -->
        <Select
          id="bc-manual-format"
          v-model="manualFormat"
          :options="supportedFormatOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          @change="onFormatChange"
        />
      </div>
      <Button
        :label="t('wallet.add.next')"
        :disabled="!manualValue.trim()"
        class="w-full"
        @click="submitManual"
      />
    </section>

    <!-- バーコード持たないカード用ボタン（タブ共通フッタ） -->
    <Button
      :label="t('wallet.add.no_barcode')"
      severity="secondary"
      text
      class="barcode-capture__no-barcode"
      @click="emitNoBarcode"
    />
  </div>
</template>

<style scoped>
.barcode-capture {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.barcode-capture__tabs {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid var(--p-surface-200, #e5e7eb);
}
.barcode-capture__tab {
  flex: 1;
  padding: 0.625rem;
  background: none;
  border: none;
  cursor: pointer;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  border-bottom: 2px solid transparent;
}
.barcode-capture__tab--active {
  color: var(--p-primary-color, #3b82f6);
  border-bottom-color: var(--p-primary-color, #3b82f6);
}
.barcode-capture__section {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  align-items: stretch;
}
.barcode-capture__hint {
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0;
}
.barcode-capture__video-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  max-width: 320px;
  margin: 0 auto;
  background: #000;
  border-radius: 0.75rem;
  overflow: hidden;
}
.barcode-capture__video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.barcode-capture__video-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 0.875rem;
  text-align: center;
  padding: 1rem;
}
.barcode-capture__btn-row {
  display: flex;
  justify-content: center;
  gap: 0.5rem;
}
.barcode-capture__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.625rem 1rem;
  border-radius: 0.5rem;
  border: 1px solid var(--p-surface-300, #d1d5db);
  background: var(--p-surface-0, #fff);
  color: var(--p-text-color, #111827);
  font-weight: 600;
  cursor: pointer;
}
.barcode-capture__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.barcode-capture__btn--primary {
  background: var(--p-primary-color, #3b82f6);
  color: #fff;
  border-color: transparent;
}
.barcode-capture__error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
  text-align: center;
}
.barcode-capture__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.barcode-capture__label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
}
.barcode-capture__file-label {
  display: inline-block;
  align-self: center;
  cursor: pointer;
}
.barcode-capture__file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.barcode-capture__no-barcode {
  margin-top: 0.5rem;
  background: none;
  border: 1px dashed var(--p-surface-300, #d1d5db);
  border-radius: 0.5rem;
  padding: 0.625rem;
  cursor: pointer;
  color: var(--p-text-muted-color, #6b7280);
  font-size: 0.875rem;
}
</style>
