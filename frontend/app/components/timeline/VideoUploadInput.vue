<script setup lang="ts">
import type { TimelineScopeType } from '~/types/timeline'

interface Props {
  scopeType: TimelineScopeType
  scopeId?: number
  disabled?: boolean
}

interface UploadedVideo {
  fileKey: string
  fileName: string
  fileSize: number
  contentType: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  uploaded: [payload: UploadedVideo]
  error: [message: string]
}>()

const api = useApi()
const { t } = useI18n()
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const uploadProgress = ref(0)

function handleClick() {
  fileInputRef.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files || input.files.length === 0) return
  const file = input.files[0]
  input.value = ''

  uploading.value = true
  uploadProgress.value = 0

  try {
    // Step 1: Presigned URL を取得
    const urlResult = await api<{
      data: { uploadUrl: string; fileKey: string; expiresInSeconds: number }
    }>('/api/v1/timeline/attachments/upload-url', {
      method: 'POST',
      body: {
        content_type: file.type,
        scope_type: props.scopeType,
        scope_id: props.scopeId ?? 0,
      },
    })

    const { uploadUrl, fileKey } = urlResult.data

    // Step 2: XMLHttpRequest で R2 に直接 PUT（進捗表示のため）
    await new Promise<void>((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      xhr.open('PUT', uploadUrl, true)
      xhr.setRequestHeader('Content-Type', file.type)

      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          uploadProgress.value = Math.round((e.loaded / e.total) * 100)
        }
      }

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve()
        } else {
          reject(new Error(`R2 アップロード失敗: status=${xhr.status}`))
        }
      }

      xhr.onerror = () => reject(new Error('R2 アップロード中にネットワークエラーが発生しました'))
      xhr.send(file)
    })

    emit('uploaded', {
      fileKey,
      fileName: file.name,
      fileSize: file.size,
      contentType: file.type,
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : t('timeline.videoUploadError')
    emit('error', message)
  } finally {
    uploading.value = false
    uploadProgress.value = 0
  }
}
</script>

<template>
  <div>
    <input
      ref="fileInputRef"
      type="file"
      accept="video/mp4,video/webm,video/quicktime"
      class="hidden"
      @change="handleFileChange"
    >

    <!-- アップロード中 -->
    <div v-if="uploading" class="flex items-center gap-2 rounded-lg border border-surface-300 p-3">
      <i class="pi pi-spin pi-spinner text-primary" />
      <div class="flex-1">
        <p class="text-sm text-surface-600">{{ $t('timeline.videoUploading') }}</p>
        <div class="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-surface-200">
          <div
            class="h-full rounded-full bg-primary transition-all"
            :style="{ width: `${uploadProgress}%` }"
          />
        </div>
        <p class="mt-0.5 text-right text-xs text-surface-400">{{ uploadProgress }}%</p>
      </div>
    </div>

    <!-- 添付ボタン -->
    <Button
      v-else
      icon="pi pi-video"
      text
      rounded
      severity="secondary"
      size="small"
      :disabled="disabled"
      :title="$t('timeline.videoUploadButton')"
      as="span"
      @click="handleClick"
    />
  </div>
</template>
