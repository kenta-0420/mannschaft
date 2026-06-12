<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))
</script>

<template>
  <div class="p-4">
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-bold">{{ $t('translation.title') }}</h1>
    </div>
    <TranslationList :org-id="orgSlug" />
  </div>
</template>
