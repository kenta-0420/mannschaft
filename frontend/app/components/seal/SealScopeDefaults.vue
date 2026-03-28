<script setup lang="ts">
import type { ScopeDefault, SealVariant } from '~/types/seal'

const props = defineProps<{
  defaults: ScopeDefault[]
}>()

const emit = defineEmits<{
  save: [defaults: ScopeDefault[]]
}>()

const localDefaults = ref<ScopeDefault[]>([...props.defaults])

const variantOptions = [
  { label: '姓', value: 'LAST_NAME' as SealVariant },
  { label: 'フルネーム', value: 'FULL_NAME' as SealVariant },
  { label: '名', value: 'FIRST_NAME' as SealVariant },
]

const scopeLabel = (d: ScopeDefault) => {
  if (d.scopeType === 'DEFAULT') return 'デフォルト'
  return d.scopeName ?? (d.scopeType === 'TEAM' ? 'チーム' : '組織')
}

function handleSave() {
  emit('save', localDefaults.value)
}

watch(() => props.defaults, (newVal) => {
  localDefaults.value = [...newVal]
})
</script>

<template>
  <div>
    <h3 class="mb-3 text-sm font-medium">スコープ別デフォルト印鑑</h3>
    <div class="space-y-3">
      <div
        v-for="(d, index) in localDefaults"
        :key="index"
        class="flex items-center gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
      >
        <span class="min-w-24 text-sm">{{ scopeLabel(d) }}</span>
        <Dropdown
          v-model="localDefaults[index].variant"
          :options="variantOptions"
          option-label="label"
          option-value="value"
          class="flex-1"
        />
      </div>
    </div>
    <div class="mt-4 flex justify-end">
      <Button label="保存" icon="pi pi-check" @click="handleSave" />
    </div>
  </div>
</template>
