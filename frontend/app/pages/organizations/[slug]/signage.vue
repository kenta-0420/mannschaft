<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
</script>

<template>
  <div>
    <!-- pageTransition(out-in) は単一要素ルートを要求するため、コンポーネント単体ルートを <div> で包む。 -->
    <SignageManager scope-type="ORGANIZATION" :scope-id="orgSlug" />
  </div>
</template>
