<script setup lang="ts">
definePageMeta({
  middleware: ['auth'],
})

const feedRef = ref<{ refresh: () => void } | null>(null)

function onPostCreated() {
  feedRef.value?.refresh()
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <h2 class="mb-4 text-2xl font-semibold">タイムライン</h2>

    <div class="mb-6">
      <TimelinePostForm scope-type="PUBLIC" @posted="onPostCreated" />
    </div>

    <TimelineFeed ref="feedRef" scope-type="PUBLIC" />
  </div>
</template>
