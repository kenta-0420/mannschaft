<script setup lang="ts">
import type { GalleryAlbum } from '~/types/gallery'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const emit = defineEmits<{
  select: [album: GalleryAlbum]
  create: []
}>()

const { getAlbums } = useGalleryApi()
const { showError } = useNotification()

const albums = ref<GalleryAlbum[]>([])
const loading = ref(false)

async function loadAlbums() {
  loading.value = true
  try {
    const res = await getAlbums(props.scopeType, props.scopeId)
    albums.value = res.data
  } catch {
    showError('アルバム一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => loadAlbums())
defineExpose({ refresh: loadAlbums })
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-semibold">ギャラリー</h2>
      <Button label="アルバム作成" icon="pi pi-plus" @click="emit('create')" />
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
          <img v-if="album.coverPhotoUrl" :src="album.coverPhotoUrl" class="h-full w-full object-cover" />
          <div v-else class="flex h-full items-center justify-center">
            <i class="pi pi-images text-3xl text-surface-300" />
          </div>
        </div>
        <div class="p-3">
          <h3 class="text-sm font-semibold">{{ album.title }}</h3>
          <p class="text-xs text-surface-400">{{ album.photoCount }}枚</p>
        </div>
      </button>
    </div>

    <div v-if="!loading && albums.length === 0" class="py-12 text-center">
      <i class="pi pi-images mb-3 text-4xl text-surface-300" />
      <p class="text-surface-400">アルバムがありません</p>
    </div>
  </div>
</template>
