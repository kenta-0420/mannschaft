<script setup lang="ts">
const { t } = useI18n()
</script>

<template>
  <section id="lp-hero" aria-labelledby="lp-hero-heading" class="pt-20 pb-16 dark:bg-surface-900">
    <div class="mx-auto max-w-5xl px-4 text-center">
      <div
        class="mb-5 inline-flex items-center gap-2 rounded-full border border-primary/30 bg-white px-4 py-1.5 text-sm font-medium text-primary shadow-sm dark:bg-surface-800"
      >
        <i class="pi pi-sparkles text-xs" />
        {{ t('landing.v2.hero.badge') }}
      </div>

      <h1
        id="lp-hero-heading"
        class="mx-auto mb-5 max-w-3xl text-3xl font-black leading-tight tracking-tight text-surface-900 dark:text-white md:text-5xl"
      >
        {{ t('landing.v2.hero.title_pre') }}
        <span class="text-primary">{{ t('landing.v2.hero.title_accent') }}</span>{{ t('landing.v2.hero.title_post') }}
      </h1>

      <p class="mx-auto mb-8 max-w-2xl text-lg leading-relaxed text-surface-600 dark:text-surface-300">
        {{ t('landing.v2.hero.subtitle') }}
      </p>

      <div class="flex flex-col items-center justify-center gap-2">
        <NuxtLink to="/register">
          <Button
            :label="t('landing.v2.hero.cta_register')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            size="large"
            class="px-10"
          />
        </NuxtLink>
        <p class="text-sm text-surface-500">
          {{ t('landing.v2.hero.login_prompt') }}
          <NuxtLink to="/login" class="font-medium text-primary hover:underline">
            {{ t('landing.v2.hero.login_link') }}
          </NuxtLink>
        </p>
      </div>

      <!-- スコープ切替（個人／チーム／組織）＋登録数カウンタ -->
      <LpScopeTabs />
    </div>
  </section>
</template>
