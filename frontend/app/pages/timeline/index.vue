<script setup lang="ts">
definePageMeta({
  middleware: ['auth'],
})

const router = useRouter()
const feedRef = ref<{ refresh: () => void } | null>(null)

function onPostCreated() {
  feedRef.value?.refresh()
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-4 flex items-center gap-3">
      <Button icon="pi pi-arrow-left" text rounded @click="router.back()" />
      <h2 class="text-2xl font-semibold">タイムライン</h2>
    </div>

    <div class="mb-6">
      <TimelinePostForm scope-type="PUBLIC" @posted="onPostCreated" />
    </div>

    <TimelineFeed ref="feedRef" scope-type="PUBLIC" />
  </div>
</template>
