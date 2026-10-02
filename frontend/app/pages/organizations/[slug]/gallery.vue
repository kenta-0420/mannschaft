<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgSlug = String(route.params.slug)
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="ギャラリー" />
    </div>
    <GalleryAlbumGrid scope-type="ORGANIZATION" :scope-id="orgSlug" />
  </div>
</template>
