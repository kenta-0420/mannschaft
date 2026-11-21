<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
</script>

<template>
  <div>
    <!-- pageTransition(out-in) は単一要素ルートを要求するため、コンポーネント単体ルートを <div> で包む。 -->
    <SignageScopePage scope-type="TEAM" :slug="teamSlug" />
  </div>
</template>
