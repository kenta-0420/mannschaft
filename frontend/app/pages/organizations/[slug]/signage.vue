<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
</script>

<template>
  <SignageManager scope-type="ORGANIZATION" :scope-id="orgSlug" />
</template>
