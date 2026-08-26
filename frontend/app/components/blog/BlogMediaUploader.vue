<script setup lang="ts">
const props = defineProps<{
  scopeType: string
  scopeId: string
  blogPostId: number
}>()

const emit = defineEmits<{
  inserted: [markdownText: string]
}>()

const { t } = useI18n()
const { uploadImage, uploadVideo } = useBlogMediaApi()
const { handleApiError } = useErrorHandler()

/*
 * 記事本文へ挿入するのは **r2Key**（`blog/TEAM/12/xxx.png`）そのものであり、URL ではない。
 *
 * 従来は公開ベース URL `config.public.r2PublicUrl` を連結した絶対 URL を挿入していたが、
 * この設定は nuxt.config に一度も宣言されておらず、null ガードも無かったため空文字へ
 * フォールバックし、`/blog/TEAM/12/xxx.png` という壊れた相対パスが本文へ永続保存されていた。
 *
 * マスター御裁可により配信は署名 URL（presigned URL）へ統一する。署名 URL には有効期限が
 * あるため本文へ焼き込むことはできない。よって本文には r2Key を保存し、記事取得時に
 * バックエンド（BlogBodyMediaResolver）が署名 URL へ解決する。
 * 公開バケットの公開ベース URL を宣言し直す案は、横断セキュリティ方針（公開バケット禁止）に
 * 反するため採らない。
 */

const imageInputRef = ref<HTMLInputElement | null>(null)
const videoInputRef = ref<HTMLInputElement | null>(null)

const uploading = ref(false)
const progress = ref(0)

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
    const markdownText = `![${file.name}](${fileKey})`
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
    const markdownText = `<video src="${fileKey}" controls></video>`
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
    >

    <!-- 非表示ファイル入力（動画） -->
    <input
      ref="videoInputRef"
      type="file"
      accept="video/mp4,video/webm"
      class="hidden"
      :aria-label="$t('blog.media.selectVideo')"
      @change="onVideoSelected"
    >

    <!-- アップロード中表示 -->
    <div v-if="uploading" class="flex items-center gap-2">
      <span class="text-xs text-surface-500">{{ $t('blog.media.uploading') }}</span>
      <progress :value="progress" max="100" class="h-2 w-24" />
      <span class="text-xs text-surface-400">{{ progress }}%</span>
    </div>
  </div>
</template>
