<script setup lang="ts">
const { t } = useI18n()
</script>

<template>
  <section id="hero" aria-labelledby="hero-heading" class="bg-surface-50 py-24 dark:bg-surface-900">
    <div class="mx-auto max-w-6xl px-4 text-center">
      <div
        class="mb-5 inline-flex items-center gap-2 rounded-full border border-primary/30 bg-white px-4 py-1.5 text-sm font-medium text-primary shadow-sm dark:bg-surface-800"
      >
        <i class="pi pi-star-fill text-xs" />
        {{ t('landing.hero.badge') }}
      </div>

      <h1
        id="hero-heading"
        class="mb-6 text-4xl font-black leading-tight tracking-tight text-surface-900 dark:text-white md:text-6xl"
      >
        {{ t('landing.hero.title_pre') }}<br />
        <span class="text-primary">{{ t('landing.hero.title_accent') }}</span>{{ t('landing.hero.title_post') }}
      </h1>

      <p class="mx-auto mb-10 max-w-2xl text-lg leading-relaxed text-surface-600 dark:text-surface-300">
        {{ t('landing.hero.subtitle') }}
      </p>

      <div class="flex flex-col items-center justify-center gap-3 sm:flex-row">
        <NuxtLink to="/register">
          <Button
            :label="t('landing.hero.cta_register')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            size="large"
            class="px-8"
          />
        </NuxtLink>
        <NuxtLink to="/login">
          <Button :label="t('landing.hero.cta_login')" severity="secondary" outlined size="large" class="px-8" />
        </NuxtLink>
      </div>

      <p class="mt-4 text-sm text-surface-400">{{ t('landing.hero.note') }}</p>
    </div>
  </section>
</template>
