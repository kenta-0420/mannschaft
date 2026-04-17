<script setup lang="ts">
import type { ProfileMediaScope } from '~/types/profileMedia'

const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  scope: ProfileMediaScope
  scopeId?: number
}>()

const emit = defineEmits<{
  uploaded: [url: string]
}>()

const { t } = useI18n()
const notification = useNotification()
const { uploadAndCommit } = useProfileMediaApi()

// ファイル入力参照
const fileInput = ref<HTMLInputElement | null>(null)
const hasImage = ref(false)

// Canvas 参照と画像要素
const canvasRef = ref<HTMLCanvasElement | null>(null)
const imgEl = ref<HTMLImageElement | null>(null)

// クロップ状態
const scale = ref(1)
const scaledW = ref(0)
const scaledH = ref(0)
const offsetX = ref(0)
const offsetY = ref(0)

// ドラッグ状態
const isDragging = ref(false)
const startX = ref(0)
const startY = ref(0)

// アップロード状態
const uploading = ref(false)

/** 値をmin〜maxの範囲にクランプする */
function clamp(val: number, min: number, max: number): number {
  return Math.min(Math.max(val, min), max)
}

/** Canvas にフレームを描画する */
function drawFrame() {
  const canvas = canvasRef.value
  const img = imgEl.value
  if (!canvas || !img) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, 300, 300)
  ctx.drawImage(img, -offsetX.value, -offsetY.value, scaledW.value, scaledH.value)
}

/** ファイル選択後の処理 */
function handleFileSelect(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return

  // バリデーション: 許可タイプ（アイコンは GIF 可）
  const allowed = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
  if (!allowed.includes(file.type)) {
    notification.error(t('profile_media.unsupported_type'))
    return
  }
  // バリデーション: サイズ上限 5MB
  if (file.size > 5 * 1024 * 1024) {
    notification.error(t('profile_media.file_too_large_icon'))
    return
  }

  const url = URL.createObjectURL(file)
  const img = new Image()
  img.onload = () => {
    imgEl.value = img
    // Canvas 300×300 に収まるよう最小スケールを算出し、中央に配置
    const s = Math.max(300 / img.naturalWidth, 300 / img.naturalHeight)
    scale.value = s
    scaledW.value = img.naturalWidth * s
    scaledH.value = img.naturalHeight * s
    offsetX.value = (scaledW.value - 300) / 2
    offsetY.value = (scaledH.value - 300) / 2
    hasImage.value = true
    nextTick(() => drawFrame())
    URL.revokeObjectURL(url)
  }
  img.src = url
}

// --- マウスイベント ---

function onMouseDown(e: MouseEvent) {
  isDragging.value = true
  startX.value = e.clientX
  startY.value = e.clientY
}

function onMouseMove(e: MouseEvent) {
  if (!isDragging.value) return
  const dx = e.clientX - startX.value
  const dy = e.clientY - startY.value
  offsetX.value = clamp(offsetX.value - dx, 0, Math.max(0, scaledW.value - 300))
  offsetY.value = clamp(offsetY.value - dy, 0, Math.max(0, scaledH.value - 300))
  startX.value = e.clientX
  startY.value = e.clientY
  drawFrame()
}

function onMouseUp() {
  isDragging.value = false
}

// --- タッチイベント（モバイル対応） ---

function onTouchStart(e: TouchEvent) {
  isDragging.value = true
  startX.value = e.touches[0].clientX
  startY.value = e.touches[0].clientY
}

function onTouchMove(e: TouchEvent) {
  if (!isDragging.value) return
  const dx = e.touches[0].clientX - startX.value
  const dy = e.touches[0].clientY - startY.value
  offsetX.value = clamp(offsetX.value - dx, 0, Math.max(0, scaledW.value - 300))
  offsetY.value = clamp(offsetY.value - dy, 0, Math.max(0, scaledH.value - 300))
  startX.value = e.touches[0].clientX
  startY.value = e.touches[0].clientY
  drawFrame()
}

function onTouchEnd() {
  isDragging.value = false
}

/** クロップを確定してアップロードする */
async function confirmCrop() {
  const img = imgEl.value
  if (!img) return

  uploading.value = true
  try {
    // 出力用 Canvas（400×400）
    const outCanvas = document.createElement('canvas')
    outCanvas.width = 400
    outCanvas.height = 400
    const ctx = outCanvas.getContext('2d')
    if (!ctx) throw new Error('Canvas context unavailable')

    // クロップ領域を元画像の座標系に変換
    const srcX = offsetX.value / scale.value
    const srcY = offsetY.value / scale.value
    const srcSize = 300 / scale.value
    ctx.drawImage(img, srcX, srcY, srcSize, srcSize, 0, 0, 400, 400)

    // Canvas → Blob → File に変換
    const blob = await new Promise<Blob>((resolve, reject) => {
      outCanvas.toBlob(
        (b) => (b ? resolve(b) : reject(new Error('toBlob failed'))),
        'image/jpeg',
        0.9,
      )
    })
    const file = new File([blob], 'icon.jpg', { type: 'image/jpeg' })

    const result = await uploadAndCommit(
      props.scope,
      props.scopeId ?? null,
      'icon',
      file,
    )
    emit('uploaded', result.url)
    visible.value = false
    hasImage.value = false
  }
  catch {
    notification.error(t('profile_media.upload_error'))
  }
  finally {
    uploading.value = false
  }
}

/** モーダルを閉じたときに状態をリセットする */
watch(visible, (val) => {
  if (!val) {
    hasImage.value = false
    imgEl.value = null
    if (fileInput.value) fileInput.value.value = ''
  }
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('profile_media.crop_title')"
    modal
    :closable="!uploading"
    class="w-full max-w-sm"
  >
    <div class="flex flex-col items-center gap-4">
      <!-- ファイル未選択時: 選択ボタン -->
      <div
        v-if="!hasImage"
        class="flex flex-col items-center gap-3 py-6"
      >
        <i class="pi pi-image text-5xl text-surface-300" />
        <Button
          :label="t('profile_media.select_file')"
          icon="pi pi-upload"
          @click="fileInput?.click()"
        />
        <input
          ref="fileInput"
          type="file"
          class="hidden"
          accept="image/jpeg,image/png,image/webp,image/gif"
          @change="handleFileSelect"
        />
      </div>

      <!-- 画像選択済み: Canvas クロッパー -->
      <template v-else>
        <p class="text-sm text-surface-500 text-center">
          {{ t('profile_media.crop_hint') }}
        </p>

        <!-- クロップキャンバス -->
        <canvas
          ref="canvasRef"
          width="300"
          height="300"
          class="rounded-lg border border-surface-200 select-none"
          :class="isDragging ? 'cursor-grabbing' : 'cursor-grab'"
          @mousedown="onMouseDown"
          @mousemove="onMouseMove"
          @mouseup="onMouseUp"
          @mouseleave="onMouseUp"
          @touchstart.passive="onTouchStart"
          @touchmove.prevent="onTouchMove"
          @touchend="onTouchEnd"
        />

        <!-- アクションボタン -->
        <div class="flex gap-2 w-full">
          <Button
            :label="t('button.cancel')"
            severity="secondary"
            class="flex-1"
            :disabled="uploading"
            @click="hasImage = false"
          />
          <Button
            :label="uploading ? t('profile_media.uploading') : t('profile_media.confirm_crop')"
            icon="pi pi-check"
            class="flex-1"
            :loading="uploading"
            @click="confirmCrop"
          />
        </div>
      </template>
    </div>
  </Dialog>
</template>
