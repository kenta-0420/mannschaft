<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = Number(route.params.id)
</script>

<template>
  <div>
    <div class="mb-4"><h1 class="text-2xl font-bold">ギャラリー</h1></div>
    <GalleryAlbumGrid scope-type="ORGANIZATION" :scope-id="orgId" />
  </div>
</template>
