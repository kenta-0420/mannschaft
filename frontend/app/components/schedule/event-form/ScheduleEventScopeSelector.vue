<script setup lang="ts">
interface ScopeOption {
  label: string
  value: string
  isPersonal: boolean
  scopeType: 'team' | 'organization'
  scopeId: string
}

defineProps<{
  scopeOptions: ScopeOption[]
}>()

const SCOPE_OVERFLOW = 5

const selectedScopeKey = defineModel<string>('selectedScopeKey', { required: true })
</script>

<template>
  <div v-if="scopeOptions && scopeOptions.length > 1" class="mb-4">
    <label class="mb-2 block text-sm font-medium text-surface-600 dark:text-surface-300">作成先</label>

    <!-- ≤5件: 横並びボタン -->
    <div v-if="scopeOptions.length <= SCOPE_OVERFLOW" class="flex flex-wrap gap-2">
      <button
        v-for="opt in scopeOptions"
        :key="opt.value"
        type="button"
        class="rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors"
        :class="selectedScopeKey === opt.value
          ? 'border-primary bg-primary/10 text-primary'
          : 'border-surface-300 text-surface-500 hover:border-surface-400 dark:border-surface-600 dark:text-surface-400'"
        @click="selectedScopeKey = opt.value"
      >
        {{ opt.label }}
      </button>
    </div>

    <!-- 6件以上: Select ドロップダウン（単一選択） -->
    <Select
      v-else
      v-model="selectedScopeKey"
      :options="scopeOptions"
      option-label="label"
      option-value="value"
      class="w-full"
      :placeholder="$t('schedule.filter.selectScope')"
    />
  </div>
</template>
