<script setup lang="ts">
import type { ElectronicSeal } from '~/types/seal'

defineProps<{
  seals: ElectronicSeal[]
}>()

const variantLabel = (variant: string) => {
  const map: Record<string, string> = { LAST_NAME: '姓', FULL_NAME: 'フルネーム', FIRST_NAME: '名' }
  return map[variant] ?? variant
}
</script>

<template>
  <div class="grid gap-4 sm:grid-cols-3">
    <div
      v-for="seal in seals"
      :key="seal.sealId"
      class="flex flex-col items-center rounded-lg border border-surface-300 p-4 dark:border-surface-600"
    >
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div class="mb-2 h-24 w-24" v-html="sanitizeHtml(seal.svgData, { allowSvg: true })" />
      <p class="text-sm font-medium">{{ seal.displayText }}</p>
      <Badge :value="variantLabel(seal.variant)" severity="secondary" class="mt-1" />
    </div>
  </div>
</template>
