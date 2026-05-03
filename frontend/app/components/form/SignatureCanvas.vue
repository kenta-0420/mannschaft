<script setup lang="ts">
const props = defineProps<{
  modelValue: string // base64 PNG or empty string
  readonly?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
const isDrawing = ref(false)

function getCtx() {
  const canvas = canvasRef.value
  if (!canvas) return null
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  ctx.lineWidth = 2
  ctx.strokeStyle = '#1a1a1a'
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  return ctx
}

function getPos(e: MouseEvent | TouchEvent, canvas: HTMLCanvasElement) {
  const rect = canvas.getBoundingClientRect()
  const scaleX = canvas.width / rect.width
  const scaleY = canvas.height / rect.height
  if (e instanceof TouchEvent) {
    const touch = e.touches[0] ?? e.changedTouches[0]
    if (!touch) return { x: 0, y: 0 }
    return {
      x: (touch.clientX - rect.left) * scaleX,
      y: (touch.clientY - rect.top) * scaleY,
    }
  }
  return {
    x: (e.clientX - rect.left) * scaleX,
    y: (e.clientY - rect.top) * scaleY,
  }
}

function onPointerDown(e: MouseEvent | TouchEvent) {
  if (props.readonly) return
  if (e instanceof TouchEvent) e.preventDefault()
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = getCtx()
  if (!ctx) return
  isDrawing.value = true
  const pos = getPos(e, canvas)
  ctx.beginPath()
  ctx.moveTo(pos.x, pos.y)
}

function onPointerMove(e: MouseEvent | TouchEvent) {
  if (!isDrawing.value || props.readonly) return
  if (e instanceof TouchEvent) e.preventDefault()
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = getCtx()
  if (!ctx) return
  const pos = getPos(e, canvas)
  ctx.lineTo(pos.x, pos.y)
  ctx.stroke()
}

function onPointerUp(e: MouseEvent | TouchEvent) {
  if (!isDrawing.value) return
  if (e instanceof TouchEvent) e.preventDefault()
  isDrawing.value = false
  emitDataUrl()
}

function onPointerLeave() {
  if (isDrawing.value) {
    isDrawing.value = false
    emitDataUrl()
  }
}

function emitDataUrl() {
  const canvas = canvasRef.value
  if (!canvas) return
  const dataUrl = canvas.toDataURL('image/png')
  emit('update:modelValue', dataUrl)
}

function clearSignature() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  emit('update:modelValue', '')
}

onMounted(() => {
  if (props.modelValue) {
    const canvas = canvasRef.value
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const img = new Image()
    img.onload = () => {
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    }
    img.src = props.modelValue
  }
})
</script>

<template>
  <div class="flex flex-col gap-2">
    <canvas
      ref="canvasRef"
      width="400"
      height="160"
      :style="{
        border: '1px solid #ccc',
        borderRadius: '4px',
        pointerEvents: readonly ? 'none' : 'auto',
        touchAction: 'none',
        display: 'block',
        maxWidth: '100%',
        background: '#fff',
      }"
      @mousedown="onPointerDown"
      @mousemove="onPointerMove"
      @mouseup="onPointerUp"
      @mouseleave="onPointerLeave"
      @touchstart.prevent="onPointerDown"
      @touchmove.prevent="onPointerMove"
      @touchend.prevent="onPointerUp"
    />
    <div v-if="!readonly" class="flex justify-end">
      <Button
        label="クリア"
        icon="pi pi-trash"
        size="small"
        severity="secondary"
        @click="clearSignature"
      />
    </div>
  </div>
</template>
