<script setup lang="ts">
defineProps<{ is503?: boolean }>()
</script>

<template>
  <div class="py-6 text-center text-surface-400">
    <i class="pi pi-chart-bar mb-2 text-3xl text-surface-300" />
    <p v-if="is503" class="text-sm">{{ $t('equipment.trending.preparing') }}</p>
    <p v-else class="text-sm">{{ $t('equipment.trending.no_data') }}</p>
  </div>
</template>
