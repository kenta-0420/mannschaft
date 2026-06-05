<script setup lang="ts">
import {
  BUCKET_DEFAULT_COLOR,
  type TodoStatusLabel,
  type TodoStatusLabelBucket,
} from '~/types/todoStatusLabel'

/**
 * F02.3.1 — TODO ステータスラベル Select
 *
 * 指定スコープ（PERSONAL / TEAM / ORGANIZATION）のラベル一覧を API からロードし、
 * 選択用の PrimeVue Select として描画する。
 * 各オプションは色付きドット + ラベル名で表現される。
 *
 * NOTE: 個人スコープ用には scopeId を渡さなくても動作する（API 側が `me` パスを使うため）。
 */
const props = defineProps<{
  scopeType: 'PERSONAL' | 'TEAM' | 'ORGANIZATION'
  scopeId: string | null
  modelValue: number | null
  /** バケットフィルタ（指定時はそのバケットのラベルのみ表示） */
  bucket?: TodoStatusLabelBucket
  disabled?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const labels = ref<TodoStatusLabel[]>([])
const loading = ref(false)
const labelApi = useTodoStatusLabelApi()

const labelScope = computed<'me' | 'team' | 'organization'>(() => {
  if (props.scopeType === 'PERSONAL') return 'me'
  if (props.scopeType === 'TEAM') return 'team'
  return 'organization'
})

async function loadLabels() {
  loading.value = true
  try {
    const res = await labelApi.listLabels(
      labelScope.value,
      props.scopeId ?? undefined,
    )
    labels.value = res.data
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.scopeType, props.scopeId],
  () => {
    void loadLabels()
  },
  { immediate: true },
)

const filteredLabels = computed(() => {
  if (!props.bucket) return labels.value
  return labels.value.filter((l) => l.bucket === props.bucket)
})

function colorOf(label: TodoStatusLabel | undefined): string {
  if (!label) return '#94a3b8'
  return label.color ?? BUCKET_DEFAULT_COLOR[label.bucket]
}

function onChange(value: number | null) {
  emit('update:modelValue', value)
}
</script>

<template>
  <Select
    :model-value="props.modelValue"
    :options="filteredLabels"
    option-label="name"
    option-value="id"
    :loading="loading"
    :disabled="disabled"
    :placeholder="placeholder"
    class="w-full"
    @update:model-value="onChange"
  >
    <template #value="{ value }">
      <template v-if="value !== null && value !== undefined">
        <span class="inline-flex items-center gap-2">
          <span
            class="inline-block h-2.5 w-2.5 rounded-full"
            :style="{ backgroundColor: colorOf(labels.find((l) => l.id === value)) }"
          />
          {{ labels.find((l) => l.id === value)?.name ?? '' }}
        </span>
      </template>
      <span v-else class="text-surface-400">{{ placeholder ?? '' }}</span>
    </template>
    <template #option="{ option }">
      <span class="inline-flex items-center gap-2">
        <span
          class="inline-block h-2.5 w-2.5 rounded-full"
          :style="{ backgroundColor: colorOf(option) }"
        />
        {{ option.name }}
      </span>
    </template>
  </Select>
</template>
