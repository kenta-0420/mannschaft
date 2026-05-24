<script setup lang="ts">
/**
 * 村カテゴリ選択コンポーネント
 *
 * 村作成フォームで使用するカテゴリ選択 UI。
 * - リストから選ぶ: 大分類→中分類→小分類のカスケードセレクト（最大3階層）
 * - 直接入力: テキスト入力でカテゴリ名を自由入力
 *
 * 選択・入力されたカテゴリ名（文字列）を `modelValue` として emit する。
 */
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'

import type { VillageCategoryResponse } from '~/types/villageCategory'

const props = defineProps<{
  /** 選択・入力されたカテゴリ名（文字列） */
  modelValue: string
  /** バリデーションエラー表示フラグ */
  invalid?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const { t } = useI18n()
const categoryApi = useVillageCategoryApi()

// =============================================================================
// モード: リストから選ぶ / 直接入力
// =============================================================================

type SelectMode = 'list' | 'free'
const selectMode = ref<SelectMode>('list')

function switchMode(mode: SelectMode) {
  if (selectMode.value === mode) return
  selectMode.value = mode
  // モード切替時に値をリセット
  selectedLevel1.value = null
  selectedLevel2.value = null
  selectedLevel3.value = null
  freeText.value = ''
  emit('update:modelValue', '')
}

// =============================================================================
// カテゴリデータ
// =============================================================================

const categories = ref<VillageCategoryResponse[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    categories.value = await categoryApi.fetchCategories()
  } catch (err) {
    console.error('VillageCategorySelect: カテゴリ取得に失敗', err)
    categories.value = []
  } finally {
    loading.value = false
  }
})

// =============================================================================
// カスケードセレクト状態
// =============================================================================

/** 大分類（ルート）選択値 */
const selectedLevel1 = ref<VillageCategoryResponse | null>(null)
/** 中分類選択値 */
const selectedLevel2 = ref<VillageCategoryResponse | null>(null)
/** 小分類選択値 */
const selectedLevel3 = ref<VillageCategoryResponse | null>(null)

/** 直接入力テキスト */
const freeText = ref('')

/** 大分類リスト（= ルートカテゴリ） */
const level1Options = computed(() => categories.value)

/** 中分類リスト（大分類選択済みの子カテゴリ） */
const level2Options = computed<VillageCategoryResponse[]>(() => {
  if (!selectedLevel1.value) return []
  return selectedLevel1.value.children ?? []
})

/** 小分類リスト（中分類選択済みの子カテゴリ） */
const level3Options = computed<VillageCategoryResponse[]>(() => {
  if (!selectedLevel2.value) return []
  return selectedLevel2.value.children ?? []
})

/** 中分類プルダウン表示: 大分類が選択済み かつ 子が存在する場合 */
const showLevel2 = computed(() => {
  return selectedLevel1.value !== null && level2Options.value.length > 0
})

/** 小分類プルダウン表示: 中分類が選択済み かつ 子が存在する場合 */
const showLevel3 = computed(() => {
  return selectedLevel2.value !== null && level3Options.value.length > 0
})

// =============================================================================
// 選択変更ハンドラ
// =============================================================================

function onLevel1Change(value: VillageCategoryResponse | null) {
  selectedLevel1.value = value
  selectedLevel2.value = null
  selectedLevel3.value = null
  emitCurrentValue()
}

function onLevel2Change(value: VillageCategoryResponse | null) {
  selectedLevel2.value = value
  selectedLevel3.value = null
  emitCurrentValue()
}

function onLevel3Change(value: VillageCategoryResponse | null) {
  selectedLevel3.value = value
  emitCurrentValue()
}

function onFreeTextInput(event: Event) {
  const target = event.target as HTMLInputElement
  freeText.value = target.value
  emit('update:modelValue', freeText.value)
}

/** 現在選択されている最深カテゴリ名を emit */
function emitCurrentValue() {
  if (selectedLevel3.value) {
    emit('update:modelValue', selectedLevel3.value.name)
  } else if (selectedLevel2.value && level3Options.value.length === 0) {
    emit('update:modelValue', selectedLevel2.value.name)
  } else if (selectedLevel1.value && level2Options.value.length === 0) {
    emit('update:modelValue', selectedLevel1.value.name)
  } else if (selectedLevel1.value && !selectedLevel2.value) {
    // 中分類がある場合は大分類選択中は未確定として空文字
    emit('update:modelValue', '')
  } else {
    emit('update:modelValue', '')
  }
}
</script>

<template>
  <div class="space-y-3">
    <!-- モード切替タブ -->
    <div class="flex gap-2">
      <button
        type="button"
        class="rounded-full px-3 py-1 text-sm font-medium transition-colors"
        :class="
          selectMode === 'list'
            ? 'bg-primary text-white'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700'
        "
        @click="switchMode('list')"
      >
        {{ t('village.categorySelect.fromList') }}
      </button>
      <button
        type="button"
        class="rounded-full px-3 py-1 text-sm font-medium transition-colors"
        :class="
          selectMode === 'free'
            ? 'bg-primary text-white'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-300 dark:hover:bg-surface-700'
        "
        @click="switchMode('free')"
      >
        {{ t('village.categorySelect.freeInput') }}
      </button>
    </div>

    <!-- リストから選ぶ -->
    <template v-if="selectMode === 'list'">
      <div v-if="loading" class="flex items-center gap-2 py-2 text-sm text-surface-500">
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
        <span>{{ $t('village.categorySelect.loading') }}</span>
      </div>
      <template v-else>
        <!-- 大分類 -->
        <Select
          :model-value="selectedLevel1"
          :options="level1Options"
          option-label="name"
          :placeholder="t('village.categorySelect.level1Placeholder')"
          :invalid="props.invalid && !selectedLevel1"
          class="w-full"
          @update:model-value="onLevel1Change"
        />

        <!-- 中分類（大分類選択済みかつ子あり） -->
        <Select
          v-if="showLevel2"
          :model-value="selectedLevel2"
          :options="level2Options"
          option-label="name"
          :placeholder="t('village.categorySelect.level2Placeholder')"
          class="w-full"
          @update:model-value="onLevel2Change"
        />

        <!-- 小分類（中分類選択済みかつ子あり） -->
        <Select
          v-if="showLevel3"
          :model-value="selectedLevel3"
          :options="level3Options"
          option-label="name"
          :placeholder="t('village.categorySelect.level3Placeholder')"
          class="w-full"
          @update:model-value="onLevel3Change"
        />
      </template>
    </template>

    <!-- 直接入力 -->
    <template v-else>
      <InputText
        :value="freeText"
        :placeholder="t('village.categorySelect.customPlaceholder')"
        :invalid="props.invalid && freeText.length === 0"
        class="w-full"
        @input="onFreeTextInput"
      />
    </template>
  </div>
</template>
