<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <PageHeader title="ギャラリー" />
    </div>
    <GalleryAlbumGrid scope-type="TEAM" :scope-id="teamSlug" />
  </div>
</template>
