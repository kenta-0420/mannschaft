<script setup lang="ts">
import type { GalleryAlbum, GalleryPhoto } from '~/types/gallery'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const emit = defineEmits<{
  select: [album: GalleryAlbum]
  create: []
}>()

const { t } = useI18n()
const { getAlbums } = useGalleryApi()
const { showError } = useNotification()

const albums = ref<GalleryAlbum[]>([])
const loading = ref(false)

// 動画プレーヤーダイアログ
const videoDialogVisible = ref(false)
const selectedVideo = ref<GalleryPhoto | null>(null)

async function loadAlbums() {
  loading.value = true
  try {
    const res = await getAlbums(props.scopeType, props.scopeId)
    albums.value = res.data
  } catch {
    showError(t('gallery.albumLoadError'))
  } finally {
    loading.value = false
  }
}

function openVideoPlayer(photo: GalleryPhoto) {
  selectedVideo.value = photo
  videoDialogVisible.value = true
}

onMounted(() => loadAlbums())
defineExpose({ refresh: loadAlbums, openVideoPlayer })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">{{ $t('gallery.title') }}</h2>
      <Button :label="$t('gallery.createAlbum')" icon="pi pi-plus" @click="emit('create')" />
    </div>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      <button
        v-for="album in albums"
        :key="album.id"
        class="overflow-hidden rounded-xl border border-surface-300 bg-surface-0 text-left transition-shadow hover:shadow-md"
        @click="emit('select', album)"
      >
        <div class="aspect-video bg-surface-100">
          <img v-if="album.coverPhotoUrl" :src="album.coverPhotoUrl" class="h-full w-full object-cover" >
          <div v-else class="flex h-full items-center justify-center">
            <i class="pi pi-images text-3xl text-surface-300" />
          </div>
        </div>
        <div class="p-3">
          <h3 class="text-sm font-semibold">{{ album.title }}</h3>
          <p class="text-xs text-surface-400">{{ album.photoCount }}{{ $t('gallery.photoCount') }}</p>
        </div>
      </button>
    </div>

    <div v-if="!loading && albums.length === 0" class="py-12 text-center">
      <i class="pi pi-images mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">{{ $t('gallery.empty') }}</p>
    </div>

    <!-- 動画プレーヤーダイアログ -->
    <Dialog
      v-model:visible="videoDialogVisible"
      modal
      :header="$t('gallery.videoPlay')"
      :style="{ width: '90vw', maxWidth: '800px' }"
      :draggable="false"
    >
      <div v-if="selectedVideo">
        <!-- 処理中 -->
        <div
          v-if="selectedVideo.processingStatus === 'PENDING' || selectedVideo.processingStatus === 'PROCESSING'"
          class="flex h-48 items-center justify-center"
        >
          <div class="text-center">
            <i class="pi pi-spin pi-spinner text-2xl" />
            <p class="mt-2 text-sm">{{ $t('gallery.videoProcessing') }}</p>
          </div>
        </div>
        <!-- 失敗 -->
        <div
          v-else-if="selectedVideo.processingStatus === 'FAILED'"
          class="flex h-48 items-center justify-center bg-surface-100"
        >
          <div class="text-center text-surface-400">
            <i class="pi pi-video text-2xl" />
            <p class="mt-2 text-sm">{{ $t('gallery.videoFailed') }}</p>
          </div>
        </div>
        <!-- 再生可能 -->
        <VideoPlayer
          v-else
          :file-key="selectedVideo.r2Key"
          :thumbnail-url="selectedVideo.thumbnailUrl ?? undefined"
          :mime-type="selectedVideo.contentType"
        />
      </div>
    </Dialog>
  </div>
</template>
