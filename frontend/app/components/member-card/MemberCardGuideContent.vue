<script setup lang="ts">
const props = defineProps<{ team: boolean }>()
const { t, tm } = useI18n()
function resolveSteps(key: string): string[] {
  const raw = tm(key) as Record<string, string> | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map(k => t(`${key}.${k}`))
}
const sections = computed(() => [
  { key: 'cards', icon: 'pi pi-id-card', color: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400' },
  { key: 'history', icon: 'pi pi-history', color: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400' },
  ...(props.team ? [{ key: 'team', icon: 'pi pi-map-marker', color: 'bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400' }] : []),
  { key: 'errors', icon: 'pi pi-info-circle', color: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400' },
])
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t('memberCardGuide.description') }}</p>
    <SectionCard v-for="section in sections" :key="section.key">
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full" :class="section.color">
          <i :class="section.icon" class="text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">{{ t(`memberCardGuide.${section.key}.title`) }}</h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">{{ t(`memberCardGuide.${section.key}.body`) }}</p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in resolveSteps(`memberCardGuide.${section.key}.steps`)" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
