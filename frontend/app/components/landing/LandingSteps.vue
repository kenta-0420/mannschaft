<script setup lang="ts">
const { t } = useI18n()

const steps = [
  { num: '01', icon: 'pi pi-user-plus', key: 0 },
  { num: '02', icon: 'pi pi-sitemap', key: 1 },
  { num: '03', icon: 'pi pi-check-circle', key: 2 },
]
</script>

<template>
  <section id="steps" aria-labelledby="steps-heading" class="bg-surface-50 py-24 dark:bg-surface-900">
    <div class="mx-auto max-w-5xl px-4">
      <div class="mb-14 text-center">
        <h2 id="steps-heading" class="text-3xl font-bold text-surface-900 dark:text-white md:text-4xl">
          {{ t('landing.steps.heading') }}
        </h2>
      </div>

      <div class="relative grid grid-cols-1 gap-8 md:grid-cols-3">
        <!-- ステップ間の横線 (デスクトップ) -->
        <div
          class="absolute top-8 hidden h-px bg-surface-200 dark:bg-surface-700 md:block"
          style="left: calc(16.67% + 2rem); right: calc(16.67% + 2rem)"
        />

        <div v-for="step in steps" :key="step.num" class="relative text-center">
          <div
            class="mx-auto mb-5 flex h-16 w-16 items-center justify-center rounded-full bg-primary text-white shadow-md"
          >
            <i :class="[step.icon, 'text-xl']" />
          </div>
          <div class="mb-1 text-xs font-bold tracking-widest text-primary">STEP {{ step.num }}</div>
          <div class="mb-2 text-lg font-bold text-surface-900 dark:text-white">
            {{ t(`landing.steps.items.${step.key}.title`) }}
          </div>
          <p class="mx-auto max-w-xs text-sm leading-relaxed text-surface-500">
            {{ t(`landing.steps.items.${step.key}.desc`) }}
          </p>
        </div>
      </div>

      <div class="mt-12 text-center">
        <NuxtLink to="/register">
          <Button :label="t('landing.steps.cta')" icon="pi pi-user-plus" size="large" class="px-10" />
        </NuxtLink>
      </div>
    </div>
  </section>
</template>
