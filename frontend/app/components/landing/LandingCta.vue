<script setup lang="ts">
const { t } = useI18n()
</script>

<template>
  <section id="cta" aria-labelledby="cta-heading" class="bg-primary py-24 text-white">
    <div class="mx-auto max-w-3xl px-4 text-center">
      <h2 id="cta-heading" class="mb-4 text-3xl font-bold md:text-4xl">
        {{ t('landing.cta.heading') }}
      </h2>
      <p class="mb-10 text-lg opacity-80">
        {{ t('landing.cta.subheading') }}
      </p>
      <div class="flex flex-col items-center justify-center gap-3 sm:flex-row">
        <NuxtLink to="/register">
          <Button :label="t('landing.cta.register')" severity="contrast" size="large" class="px-8" />
        </NuxtLink>
        <NuxtLink to="/login">
          <Button
            :label="t('landing.cta.login')"
            text
            size="large"
            class="px-8 text-white hover:text-white/80"
          />
        </NuxtLink>
      </div>
    </div>
  </section>
</template>
