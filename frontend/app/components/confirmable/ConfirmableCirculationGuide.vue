<script setup lang="ts">
const props = defineProps<{
  currentFeature: 'quickConfirm' | 'circulation'
  targetPath: string
}>()

const descriptionKey = computed(() => `confirmable.guide.${props.currentFeature}.description`)
const linkKey = computed(() => `confirmable.guide.${props.currentFeature}.link`)
</script>

<template>
  <SectionCard class="mt-4" data-testid="confirmable-circulation-guide">
    <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <p class="text-sm leading-6 text-surface-600 dark:text-surface-300">
        {{ $t(descriptionKey) }}
      </p>
      <NuxtLink
        :to="targetPath"
        class="inline-flex min-h-11 shrink-0 items-center gap-2 rounded-lg px-3 font-medium text-primary-600 hover:bg-primary-50 dark:text-primary-300 dark:hover:bg-primary-950"
      >
        {{ $t(linkKey) }}
        <i class="pi pi-arrow-right" aria-hidden="true" />
      </NuxtLink>
    </div>
  </SectionCard>
</template>
