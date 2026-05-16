<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgId = Number(route.params.id)
</script>

<template>
  <SignageManager scope-type="ORGANIZATION" :scope-id="orgId" />
</template>
