<script setup lang="ts">
import type { ChartPhoto } from '~/types/chart'

const props = defineProps<{
  chartId: number
  photos: ChartPhoto[]
}>()

const emit = defineEmits<{
  upload: [file: File, type: string]
}>()

const beforePhotos = computed(() => props.photos.filter(p => p.photoType === 'BEFORE'))
const afterPhotos = computed(() => props.photos.filter(p => p.photoType === 'AFTER'))

function handleUpload(event: Event, type: string) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) emit('upload', file, type)
}
</script>

<template>
  <div>
    <h4 class="mb-3 text-sm font-medium">ビフォーアフター</h4>
    <div class="grid gap-4 md:grid-cols-2">
      <div>
        <p class="mb-2 text-xs font-medium text-surface-500">ビフォー</p>
        <div class="grid grid-cols-2 gap-2">
          <img
            v-for="photo in beforePhotos"
            :key="photo.id"
            :src="photo.photoUrl"
            alt="ビフォー"
            class="h-32 w-full rounded-lg object-cover"
          />
        </div>
        <label class="mt-2 inline-block cursor-pointer">
          <input type="file" accept="image/*" class="hidden" @change="handleUpload($event, 'BEFORE')" />
          <Button label="ビフォー追加" icon="pi pi-camera" size="small" severity="secondary" outlined as="span" />
        </label>
      </div>
      <div>
        <p class="mb-2 text-xs font-medium text-surface-500">アフター</p>
        <div class="grid grid-cols-2 gap-2">
          <img
            v-for="photo in afterPhotos"
            :key="photo.id"
            :src="photo.photoUrl"
            alt="アフター"
            class="h-32 w-full rounded-lg object-cover"
          />
        </div>
        <label class="mt-2 inline-block cursor-pointer">
          <input type="file" accept="image/*" class="hidden" @change="handleUpload($event, 'AFTER')" />
          <Button label="アフター追加" icon="pi pi-camera" size="small" severity="secondary" outlined as="span" />
        </label>
      </div>
    </div>
  </div>
</template>
