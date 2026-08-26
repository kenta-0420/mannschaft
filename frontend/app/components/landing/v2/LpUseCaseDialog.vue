<script setup lang="ts">
/**
 * LP v2: 業種プリセットのモーダル（LpFeatureDialog を金型に踏襲）。
 *
 * ページ遷移させず、その場で業種の内容を段階開示する。
 * 表示素材は既存の業種ページと共通の landing.v2.usecase_pages.* を流用。
 * ページ遷移はさせない方針のため /use-cases/{slug} への「詳しく見る」リンクは付けない。
 */
export interface LpUseCase {
  key: string
  icon: string
  // usecase_pages のキー（アンダースコア区切り。例: sports_team）
  pageKey: string
}

const props = defineProps<{
  visible: boolean
  useCase: LpUseCase | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const { t, tm, rt } = useI18n()

const visibleProxy = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

// 配列ロケールは tm() + rt() で解決する（t() は文字列しか返せない）
const story = computed(() => {
  if (!props.useCase) return []
  const raw: unknown = tm(`landing.v2.usecase_pages.${props.useCase.pageKey}.story`)
  if (Array.isArray(raw)) return raw.map((item) => rt(item as Parameters<typeof rt>[0]))
  return []
})

const chips = computed(() => {
  if (!props.useCase) return []
  const raw: unknown = tm(`landing.v2.usecase_pages.${props.useCase.pageKey}.chips`)
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
    :header="useCase ? t(`landing.v2.usecase_pages.${useCase.pageKey}.title`) : ''"
    :style="{ width: '32rem' }"
    class="mx-4"
  >
    <div v-if="useCase" class="flex flex-col gap-4 text-left">
      <!-- 困りごと -->
      <div class="flex items-start gap-3">
        <div class="flex h-12 w-12 shrink-0 items-center justify-center rounded-xl bg-primary/10">
          <i :class="[useCase.icon, 'text-xl text-primary']" />
        </div>
        <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
          {{ t(`landing.v2.usecase_pages.${useCase.pageKey}.problem`) }}
        </p>
      </div>

      <!-- Mannschaftならこうなる（ストーリー） -->
      <div v-if="story.length > 0" class="rounded-xl bg-surface-50 p-4 dark:bg-surface-900">
        <p class="mb-3 text-xs font-semibold text-surface-500">
          {{ t('landing.v2.usecase_pages.common.story_heading') }}
        </p>
        <ol class="space-y-2.5">
          <li
            v-for="(item, idx) in story"
            :key="idx"
            class="flex items-start gap-2.5 text-sm text-surface-700 dark:text-surface-300"
          >
            <span
              class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[0.65rem] font-bold text-primary"
            >
              {{ idx + 1 }}
            </span>
            <span class="leading-relaxed">{{ item }}</span>
          </li>
        </ol>
      </div>

      <!-- 使う機能チップ -->
      <div v-if="chips.length > 0">
        <p class="mb-2 text-xs font-semibold text-surface-500">
          {{ t('landing.v2.usecase_pages.common.chips_heading') }}
        </p>
        <div class="flex flex-wrap gap-2">
          <span
            v-for="(chip, idx) in chips"
            :key="idx"
            class="rounded-full bg-primary/10 px-3 py-1 text-xs font-medium text-primary"
          >
            {{ chip }}
          </span>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex w-full items-center justify-between gap-2">
        <Button
          :label="t('landing.v2.features.close')"
          severity="secondary"
          outlined
          size="small"
          @click="visibleProxy = false"
        />
        <NuxtLink to="/register">
          <Button
            :label="t('landing.v2.usecase_pages.common.cta')"
            icon="pi pi-arrow-right"
            icon-pos="right"
            size="small"
          />
        </NuxtLink>
      </div>
    </template>
  </Dialog>
</template>
