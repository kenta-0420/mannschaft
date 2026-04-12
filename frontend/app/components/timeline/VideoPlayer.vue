<script setup lang="ts">
import type { VideoProcessingStatus } from '~/types/timeline'

interface Props {
  fileKey: string
  thumbnailUrl?: string
  processingStatus?: VideoProcessingStatus
  mimeType?: string
}

const props = defineProps<Props>()
const { resolveUrl } = useR2Url()

const isProcessing = computed(() =>
  props.processingStatus === 'PENDING' || props.processingStatus === 'PROCESSING',
)

const isFailed = computed(() => props.processingStatus === 'FAILED')

const videoSrc = computed(() => {
  if (isProcessing.value || isFailed.value) return ''
  return resolveUrl(props.fileKey)
})
</script>

<template>
  <div class="relative overflow-hidden rounded-lg bg-black">
    <!-- 処理中 -->
    <div v-if="isProcessing" class="flex h-48 items-center justify-center">
      <div class="text-center text-white">
        <i class="pi pi-spin pi-spinner text-2xl" />
        <p class="mt-2 text-sm">{{ $t('timeline.videoProcessing') }}</p>
      </div>
    </div>
    <!-- 失敗 -->
    <div v-else-if="isFailed" class="flex h-48 items-center justify-center bg-surface-800">
      <div class="text-center text-surface-400">
        <i class="pi pi-video text-2xl" />
        <p class="mt-2 text-sm">{{ $t('timeline.videoFailed') }}</p>
      </div>
    </div>
    <!-- 再生可能 -->
    <video
      v-else
      controls
      class="w-full"
      :poster="thumbnailUrl"
      preload="metadata"
    >
      <source v-if="videoSrc" :src="videoSrc" :type="mimeType || 'video/mp4'" />
    </video>
  </div>
</template>
