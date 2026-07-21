<script setup lang="ts">
/** 特定商取引法に基づく表記の本文。ページ（commerce-disclosure.vue）から利用する。 */
const { t, tm, rt } = useI18n()

interface DisclosureItem {
  key: string
  label: string
  value: string
}

// 項目は landing.legal.commerce.items（キー＝項目名のオブジェクト方式）。tm() + rt() で解決する（t() は文字列しか返せない）
const items = computed<DisclosureItem[]>(() => {
  const raw: unknown = tm('landing.legal.commerce.items')
  if (!raw || typeof raw !== 'object') return []
  return Object.entries(raw as Record<string, unknown>).map(([key, entry]) => {
    const e = entry as { label?: unknown; value?: unknown }
    return {
      key,
      label: e.label === undefined ? '' : rt(e.label as Parameters<typeof rt>[0]),
      value: e.value === undefined ? '' : rt(e.value as Parameters<typeof rt>[0]),
    }
  })
})
</script>

<template>
  <div>
    <h1 class="mb-2 text-3xl font-bold text-surface-900 dark:text-white">
      {{ t('landing.legal.commerce.title') }}
    </h1>

    <dl class="mt-8 space-y-6 text-left">
      <div
        v-for="item in items"
        :id="item.key === 'contact' ? 'contact' : undefined"
        :key="item.key"
        class="scroll-mt-20 border-b border-surface-100 pb-4 last:border-b-0 dark:border-surface-700"
      >
        <dt class="mb-1 text-sm font-bold text-surface-900 dark:text-white">
          {{ item.label }}
        </dt>
        <dd class="leading-relaxed text-surface-600 dark:text-surface-300">
          {{ item.value }}
        </dd>
      </div>
    </dl>
  </div>
</template>
