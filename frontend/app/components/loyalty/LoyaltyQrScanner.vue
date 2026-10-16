<script setup lang="ts">
import { useQrScanner } from '~/composables/jobs/useQrScanner'
defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ scanned: [token: string] }>()
const { t } = useI18n()
const scanner = useQrScanner()
const video = ref<HTMLVideoElement | null>(null)
const failed = ref(false)
async function start() {
  if (!video.value) return
  failed.value = false
  try {
    await scanner.start({
      videoEl: video.value,
      onScanned: (result) => {
        scanner.stop()
        emit('scanned', result.text)
      },
    })
  } catch {
    failed.value = true
  }
}
onUnmounted(scanner.stop)
</script>
<template>
  <div class="space-y-3">
    <video ref="video" class="w-full max-w-sm rounded-lg" :aria-label="t('loyalty.scan.camera')" />
    <p v-if="failed" role="alert">{{ t('loyalty.scan.cameraDenied') }}</p>
    <Button
      class="min-h-11"
      :label="t('loyalty.scan.camera')"
      :disabled="disabled"
      icon="pi pi-camera"
      @click="start"
    />
  </div>
</template>
