<script setup lang="ts">
export interface LpFeature {
  key: string
  icon: string
  slug: string | null
}

const props = defineProps<{
  visible: boolean
  feature: LpFeature | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const { t, tm, rt } = useI18n()

const visibleProxy = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

// できること（箇条書き）。配列ロケールは tm() + rt() で解決する（t() は文字列しか返せない）
const points = computed(() => {
  if (!props.feature) return []
  const raw: unknown = tm(`landing.v2.features.items.${props.feature.key}.points`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})
</script>

<template>
  <Dialog
    v-model:visible="visibleProxy"
    modal
    dismissable-mask
    :dismissable-mask-on-tab="false"
    :header="feature ? t(`landing.v2.features.items.${feature.key}.title`) : ''"
    :style="{ width: '28rem' }"
    class="mx-4"
  >
    <div v-if="feature" class="flex flex-col gap-4">
      <div class="flex items-start gap-3">
        <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-primary/10">
          <i :class="[feature.icon, 'text-xl text-primary']" />
        </div>
        <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
          {{ t(`landing.v2.features.items.${feature.key}.desc`) }}
        </p>
      </div>

      <!-- できること（実アプリの使い方ガイドからの要約） -->
      <div v-if="points.length > 0" class="rounded-xl bg-surface-50 p-4 dark:bg-surface-900">
        <p class="mb-2 text-xs font-semibold text-surface-500">
          {{ t('landing.v2.features.can_do_label') }}
        </p>
        <ul class="space-y-1.5">
          <li
            v-for="(point, idx) in points"
            :key="idx"
            class="flex items-start gap-2 text-sm text-surface-700 dark:text-surface-300"
          >
            <i class="pi pi-check-circle mt-0.5 shrink-0 text-xs text-primary" />
            <span>{{ point }}</span>
          </li>
        </ul>
      </div>

      <div v-if="feature.slug" class="border-t border-surface-200 pt-3 dark:border-surface-700">
        <NuxtLink
          :to="`/features/${feature.slug}`"
          class="inline-flex items-center gap-1.5 text-sm font-semibold text-primary hover:underline"
        >
          {{ t('landing.v2.features.detail_link') }}
          <i class="pi pi-arrow-right text-xs" />
        </NuxtLink>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('landing.v2.features.close')"
        severity="secondary"
        outlined
        size="small"
        @click="visibleProxy = false"
      />
    </template>
  </Dialog>
</template>
