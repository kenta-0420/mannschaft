<script setup lang="ts">
import type { TagResponse, CreateTagRequest } from '~/types/quickMemo'

const props = defineProps<{
  modelValue: number[]
  tags: TagResponse[]
  creating?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [ids: number[]]
  create: [req: CreateTagRequest]
}>()

const { t } = useI18n()
const showCreate = ref(false)
const newTagName = ref('')
const newTagColor = ref('#6366f1')

const selectedIds = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const selectedTags = computed(() =>
  props.tags.filter((tag) => selectedIds.value.includes(tag.id)),
)

function toggleTag(id: number) {
  const next = selectedIds.value.includes(id)
    ? selectedIds.value.filter((v) => v !== id)
    : [...selectedIds.value, id]
  emit('update:modelValue', next)
}

function submitCreate() {
  const name = newTagName.value.trim()
  if (!name) return
  emit('create', { name, color: newTagColor.value })
  newTagName.value = ''
  showCreate.value = false
}
</script>

<template>
  <div class="space-y-2">
    <!-- 選択済みタグ表示 -->
    <div v-if="selectedTags.length > 0" class="flex flex-wrap gap-1">
      <span
        v-for="tag in selectedTags"
        :key="tag.id"
        class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium text-white"
        :style="{ backgroundColor: tag.color ?? '#6366f1' }"
      >
        {{ tag.name }}
        <button
          type="button"
          class="hover:opacity-75"
          @click="toggleTag(tag.id)"
        >
          <i class="pi pi-times text-[10px]" />
        </button>
      </span>
    </div>

    <!-- タグ選択リスト -->
    <div class="flex flex-wrap gap-1">
      <button
        v-for="tag in tags"
        :key="tag.id"
        type="button"
        class="rounded-full border px-2 py-0.5 text-xs transition-colors"
        :class="
          selectedIds.includes(tag.id)
            ? 'border-transparent text-white'
            : 'border-surface-300 bg-surface-0 text-surface-600 hover:border-primary/50 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-300'
        "
        :style="selectedIds.includes(tag.id) ? { backgroundColor: tag.color ?? '#6366f1' } : {}"
        @click="toggleTag(tag.id)"
      >
        {{ tag.name }}
      </button>

      <!-- 新規タグ作成トグル -->
      <button
        type="button"
        class="rounded-full border border-dashed border-surface-400 px-2 py-0.5 text-xs text-surface-500 hover:border-primary hover:text-primary dark:border-surface-500 dark:text-surface-400"
        @click="showCreate = !showCreate"
      >
        <i class="pi pi-plus text-[10px]" />
        {{ t('quick_memo.tag.create') }}
      </button>
    </div>

    <!-- タグ作成フォーム -->
    <div v-if="showCreate" class="flex items-center gap-2">
      <input
        v-model="newTagName"
        type="text"
        :placeholder="t('quick_memo.tag.name_placeholder')"
        maxlength="50"
        class="h-7 flex-1 rounded border border-surface-300 bg-surface-0 px-2 text-xs focus:border-primary focus:outline-none dark:border-surface-600 dark:bg-surface-700"
        @keydown.enter.prevent="submitCreate"
      >
      <input v-model="newTagColor" type="color" class="h-7 w-7 cursor-pointer rounded border border-surface-300" >
      <Button
        size="small"
        :label="t('button.create')"
        :loading="creating"
        class="h-7 text-xs"
        @click="submitCreate"
      />
    </div>
  </div>
</template>
