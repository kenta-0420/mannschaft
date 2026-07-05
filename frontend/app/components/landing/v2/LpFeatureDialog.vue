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

// v2 の機能 slug → 旧 landing.features の index（主要機能/利用シーンの実データを引くため）。
// slug が無い機能（reservation/timeline 等）は展開データを持たないためトグルを出さない。
const LEGACY_SLUG_INDEX: Record<string, number> = {
  team: 0,
  calendar: 1,
  chat: 2,
  shift: 3,
  forms: 4,
  gallery: 5,
  billing: 6,
  matching: 7,
}

const visibleProxy = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

const expanded = ref(false)

// モーダルを開くたび／機能が変わるたびに折りたたみへリセット
watch(
  () => [props.visible, props.feature?.key],
  () => {
    expanded.value = false
  },
)

// できること（実アプリの使い方ガイド要約）。配列は tm() + rt() で解決する（t() は文字列しか返せない）
const points = computed(() => {
  if (!props.feature) return []
  const raw: unknown = tm(`landing.v2.features.items.${props.feature.key}.points`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})

const legacyIndex = computed<number | null>(() => {
  if (!props.feature?.slug) return null
  const idx = LEGACY_SLUG_INDEX[props.feature.slug]
  return idx === undefined ? null : idx
})

// 展開時に見せる「主要機能」「利用シーン」（旧 landing.features の実データを流用）
const keyFeatures = computed(() => {
  if (legacyIndex.value === null) return []
  const raw: unknown = tm(`landing.features.items.${legacyIndex.value}.features`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})

const useCases = computed(() => {
  if (legacyIndex.value === null) return []
  const raw: unknown = tm(`landing.features.items.${legacyIndex.value}.useCases`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})

// 展開できる追加情報があるときだけトグルを出す
const hasMoreDetail = computed(() => keyFeatures.value.length > 0 || useCases.value.length > 0)
</script>

<template>
  <Dialog
    v-model:visible="visibleProxy"
    modal
    dismissable-mask
    :dismissable-mask-on-tab="false"
    :header="feature ? t(`landing.v2.features.items.${feature.key}.title`) : ''"
    :style="{ width: '30rem' }"
    class="mx-4"
  >
    <div v-if="feature" class="flex flex-col gap-4 text-left">
      <div class="flex items-start gap-3">
        <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-primary/10">
          <i :class="[feature.icon, 'text-xl text-primary']" />
        </div>
        <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
          {{ t(`landing.v2.features.items.${feature.key}.desc`) }}
        </p>
      </div>

      <!-- できること（折りたたみ時も常に表示） -->
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

      <!-- もっと詳しく（ページ遷移せずモーダル自体を伸縮させて段階開示） -->
      <template v-if="hasMoreDetail">
        <!-- grid-template-rows 0fr→1fr で高さを CSS のみで滑らかに伸縮させる -->
        <div class="lp-expand" :class="{ 'is-open': expanded }">
          <div class="lp-expand-inner">
            <div class="flex flex-col gap-4 pt-1">
              <div v-if="keyFeatures.length > 0">
                <p class="mb-2 text-xs font-semibold text-surface-500">
                  {{ t('landing.features_detail.key_features') }}
                </p>
                <ul class="space-y-1.5">
                  <li
                    v-for="(item, idx) in keyFeatures"
                    :key="idx"
                    class="flex items-start gap-2 text-sm text-surface-700 dark:text-surface-300"
                  >
                    <i class="pi pi-check-circle mt-0.5 shrink-0 text-xs text-primary" />
                    <span>{{ item }}</span>
                  </li>
                </ul>
              </div>

              <div v-if="useCases.length > 0">
                <p class="mb-2 text-xs font-semibold text-surface-500">
                  {{ t('landing.features_detail.use_cases') }}
                </p>
                <ul class="space-y-1.5">
                  <li
                    v-for="(item, idx) in useCases"
                    :key="idx"
                    class="flex items-start gap-2 text-sm text-surface-700 dark:text-surface-300"
                  >
                    <i class="pi pi-arrow-right mt-0.5 shrink-0 text-xs text-primary" />
                    <span>{{ item }}</span>
                  </li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        <button
          type="button"
          class="inline-flex items-center gap-1.5 self-start text-sm font-semibold text-primary hover:underline"
          :aria-expanded="expanded"
          @click="expanded = !expanded"
        >
          {{ expanded ? t('landing.v2.features.less_label') : t('landing.v2.features.more_label') }}
          <i class="pi pi-chevron-down text-xs transition-transform duration-200" :class="{ 'rotate-180': expanded }" />
        </button>
      </template>
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

<style scoped>
/* grid-template-rows のトラック伸縮で高さを CSS のみで滑らかにアニメーションさせる */
.lp-expand {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.25s ease;
}
.lp-expand.is-open {
  grid-template-rows: 1fr;
}
.lp-expand-inner {
  overflow: hidden;
  min-height: 0;
}
@media (prefers-reduced-motion: reduce) {
  .lp-expand {
    transition: none;
  }
}
</style>
