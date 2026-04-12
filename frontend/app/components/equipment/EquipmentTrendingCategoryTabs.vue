<script setup lang="ts">
const props = defineProps<{
  categories: string[]
  modelValue: string | null  // null = 全カテゴリ
}>()

const emit = defineEmits<{
  'update:modelValue': [val: string | null]
}>()

const ALL_KEY = '__ALL__'

// タブ表示は最大5件。それ以上はドロップダウン
const MAX_TABS = 5
const visibleTabs = computed(() => props.categories.slice(0, MAX_TABS))
const overflowCategories = computed(() => props.categories.slice(MAX_TABS))
const hasOverflow = computed(() => overflowCategories.value.length > 0)

const selectedTab = computed({
  get: () => props.modelValue ?? ALL_KEY,
  set: (v: string) => emit('update:modelValue', v === ALL_KEY ? null : v),
})
</script>

<template>
  <div class="mb-3 flex flex-wrap gap-1">
    <button
      :class="[
        'rounded-full px-3 py-0.5 text-xs font-medium transition-colors',
        selectedTab === ALL_KEY
          ? 'bg-primary text-white'
          : 'bg-surface-100 text-surface-600 hover:bg-surface-200',
      ]"
      @click="selectedTab = ALL_KEY"
    >
      {{ $t('equipment.trending.all_categories') }}
    </button>
    <button
      v-for="cat in visibleTabs"
      :key="cat"
      :class="[
        'rounded-full px-3 py-0.5 text-xs font-medium transition-colors',
        selectedTab === cat
          ? 'bg-primary text-white'
          : 'bg-surface-100 text-surface-600 hover:bg-surface-200',
      ]"
      @click="selectedTab = cat"
    >
      {{ cat }}
    </button>
    <select
      v-if="hasOverflow"
      v-model="selectedTab"
      class="rounded-full border border-surface-200 bg-surface-50 px-2 py-0.5 text-xs text-surface-600"
    >
      <option v-for="cat in overflowCategories" :key="cat" :value="cat">{{ cat }}</option>
    </select>
  </div>
</template>
