<script setup lang="ts">
const props = defineProps<{
  scopeType: string
  scopeId: number
  blogPostId: number
}>()

const emit = defineEmits<{
  inserted: [markdownText: string]
}>()

const { t } = useI18n()
const { uploadImage, uploadVideo } = useBlogMediaApi()
const { handleApiError } = useErrorHandler()
const config = useRuntimeConfig()

/** R2 Public ベース URL（末尾スラッシュなし） */
const r2PublicUrl = computed<string>(() => {
  const url = config.public.r2PublicUrl as string | undefined
  return url ? url.replace(/\/$/, '') : ''
})

const imageInputRef = ref<HTMLInputElement | null>(null)
const videoInputRef = ref<HTMLInputElement | null>(null)

const uploading = ref(false)
const progress = ref(0)

function buildPublicUrl(fileKey: string): string {
  return `${r2PublicUrl.value}/${fileKey}`
}

/** 画像アップロードボタンクリック */
function onClickUploadImage() {
  imageInputRef.value?.click()
}

/** 動画アップロードボタンクリック */
function onClickUploadVideo() {
  videoInputRef.value?.click()
}

/** 画像ファイル選択時の処理 */
async function onImageSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  uploading.value = true
  progress.value = 0

  try {
    const { fileKey } = await uploadImage({
      file,
      scopeType: props.scopeType,
      scopeId: props.scopeId,
      blogPostId: props.blogPostId,
    })

    progress.value = 100
    const publicUrl = buildPublicUrl(fileKey)
    const markdownText = `![${file.name}](${publicUrl})`
    emit('inserted', markdownText)
  }
  catch (error) {
    handleApiError(error, t('blog.media.uploadError'))
  }
  finally {
    uploading.value = false
    progress.value = 0
    // inputをリセット（同じファイルを再選択できるように）
    input.value = ''
  }
}

/** 動画ファイル選択時の処理 */
async function onVideoSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  uploading.value = true
  progress.value = 0

  try {
    const { fileKey } = await uploadVideo({
      file,
      scopeType: props.scopeType,
      scopeId: props.scopeId,
      blogPostId: props.blogPostId,
      onProgress: (p) => {
        progress.value = p
      },
    })

    progress.value = 100
    const publicUrl = buildPublicUrl(fileKey)
    const markdownText = `<video src="${publicUrl}" controls></video>`
    emit('inserted', markdownText)
  }
  catch (error) {
    handleApiError(error, t('blog.media.uploadError'))
  }
  finally {
    uploading.value = false
    progress.value = 0
    // inputをリセット（同じファイルを再選択できるように）
    input.value = ''
  }
}
</script>

<template>
  <div class="flex items-center gap-2">
    <!-- 画像アップロードボタン -->
    <Button
      :label="$t('blog.media.uploadImage')"
      icon="pi pi-image"
      size="small"
      outlined
      :disabled="uploading"
      @click="onClickUploadImage"
    />

    <!-- 動画アップロードボタン -->
    <Button
      :label="$t('blog.media.uploadVideo')"
      icon="pi pi-video"
      size="small"
      outlined
      :disabled="uploading"
      @click="onClickUploadVideo"
    />

    <!-- 非表示ファイル入力（画像） -->
    <input
      ref="imageInputRef"
      type="file"
      accept="image/*"
      class="hidden"
      :aria-label="$t('blog.media.selectImage')"
      @change="onImageSelected"
    />

    <!-- 非表示ファイル入力（動画） -->
    <input
      ref="videoInputRef"
      type="file"
      accept="video/mp4,video/webm"
      class="hidden"
      :aria-label="$t('blog.media.selectVideo')"
      @change="onVideoSelected"
    />

    <!-- アップロード中表示 -->
    <div v-if="uploading" class="flex items-center gap-2">
      <span class="text-xs text-surface-500">{{ $t('blog.media.uploading') }}</span>
      <progress :value="progress" max="100" class="h-2 w-24" />
      <span class="text-xs text-surface-400">{{ progress }}%</span>
    </div>
  </div>
</template>
