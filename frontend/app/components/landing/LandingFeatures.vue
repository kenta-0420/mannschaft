<script setup lang="ts">
const { t } = useI18n()

const features = [
  { icon: 'pi pi-users', key: 0 },
  { icon: 'pi pi-calendar', key: 1 },
  { icon: 'pi pi-comments', key: 2 },
  { icon: 'pi pi-clock', key: 3 },
  { icon: 'pi pi-file-edit', key: 4 },
  { icon: 'pi pi-images', key: 5 },
  { icon: 'pi pi-credit-card', key: 6 },
  { icon: 'pi pi-trophy', key: 7 },
]
</script>

<template>
  <section id="features" aria-labelledby="features-heading" class="py-24">
    <div class="mx-auto max-w-6xl px-4">
      <div class="mb-14 text-center">
        <h2 id="features-heading" class="text-3xl font-bold text-surface-900 dark:text-white md:text-4xl">
          {{ t('landing.features.heading') }}
        </h2>
        <p class="mt-4 text-surface-500">{{ t('landing.features.subheading') }}</p>
      </div>

      <div class="grid grid-cols-2 gap-4 md:grid-cols-4">
        <div
          v-for="f in features"
          :key="f.key"
          class="rounded-xl border border-surface-200 bg-white p-5 transition-all duration-200 hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
        >
          <i :class="[f.icon, 'mb-3 block text-2xl text-primary']" />
          <div class="mb-1 font-semibold text-surface-800 dark:text-white">
            {{ t(`landing.features.items.${f.key}.title`) }}
          </div>
          <div class="text-sm leading-relaxed text-surface-500">
            {{ t(`landing.features.items.${f.key}.desc`) }}
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
