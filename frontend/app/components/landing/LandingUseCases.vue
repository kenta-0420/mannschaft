<script setup lang="ts">
const { t } = useI18n()

const useCases = [
  { icon: 'pi pi-flag', color: 'text-blue-500', bg: 'bg-blue-50 dark:bg-blue-950', key: 0 },
  { icon: 'pi pi-home', color: 'text-green-500', bg: 'bg-green-50 dark:bg-green-950', key: 1 },
  { icon: 'pi pi-building', color: 'text-purple-500', bg: 'bg-purple-50 dark:bg-purple-950', key: 2 },
  { icon: 'pi pi-graduation-cap', color: 'text-orange-500', bg: 'bg-orange-50 dark:bg-orange-950', key: 3 },
]
</script>

<template>
  <section id="use-cases" aria-labelledby="use-cases-heading" class="py-24">
    <div class="mx-auto max-w-6xl px-4">
      <div class="mb-14 text-center">
        <h2 id="use-cases-heading" class="text-3xl font-bold text-surface-900 dark:text-white md:text-4xl">
          {{ t('landing.useCases.heading') }}
        </h2>
        <p class="mt-4 text-surface-500">{{ t('landing.useCases.subheading') }}</p>
      </div>

      <div class="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-4">
        <div
          v-for="uc in useCases"
          :key="uc.key"
          :class="['rounded-xl p-6', uc.bg]"
        >
          <i :class="[uc.icon, 'mb-4 block text-3xl', uc.color]" />
          <div class="mb-2 font-bold text-surface-900 dark:text-white">
            {{ t(`landing.useCases.items.${uc.key}.title`) }}
          </div>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`landing.useCases.items.${uc.key}.desc`) }}
          </p>
        </div>
      </div>
    </div>
  </section>
</template>
