<script setup lang="ts">
/**
 * F20.1 課金機能の使い方ガイド本体（カード方式・SecurityHelpContent を金型に踏襲）。
 * i18n: billing.plans.help.* / billing.manage.help.*（呼び出し元 variant で切替。内容は同一4カード）。
 */
const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    /** 呼び出し元により見出しの前置キーを変える（plans 画面 / manage 画面で文言セットが分かれる）。 */
    variant?: 'plans' | 'manage'
  }>(),
  { variant: 'plans' },
)

const base = computed(() => `billing.${props.variant}.help`)
</script>

<template>
  <div class="space-y-4">
    <!-- プランとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-th-large text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(`${base}.plan.title`) }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`${base}.plan.body`) }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- アドオンとは -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-plus-circle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(`${base}.addon.title`) }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`${base}.addon.body`) }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- ベータ期間中の扱い -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-gift text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(`${base}.beta.title`) }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`${base}.beta.body`) }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- ベータ特典との違い -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-star text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(`${base}.betaGrant.title`) }}
          </h2>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`${base}.betaGrant.body`) }}
          </p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
