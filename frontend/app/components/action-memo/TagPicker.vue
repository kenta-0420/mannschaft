<script setup lang="ts">
import type { ActionMemoTag, CreateTagPayload } from '~/types/actionMemo'

/**
 * F02.5 TagPicker コンポーネント（Phase 4）。
 *
 * <p>設計書 §4.x に基づくインクリメンタルサーチ + 新規作成サジェスト。
 * 選択済みタグをチップ表示し、× ボタンで除去できる。
 * 論理削除済みタグはサジェスト候補に表示しない（§3）。</p>
 *
 * <p>props の {@code modelValue} は選択済みタグ ID の配列。
 * {@code memoId} が指定された場合は編集モード（メモへのタグ追加/除去 API を呼ぶ）。</p>
 */

const props = defineProps<{
  modelValue: number[]
  memoId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number[]]
}>()

const { t } = useI18n()
const store = useActionMemoStore()

const MAX_TAGS_PER_MEMO = 10
const MAX_TAGS_TOTAL = 100

const searchQuery = ref('')
const showDropdown = ref(false)
const showCreateForm = ref(false)
const newTagName = ref('')
const newTagColor = ref('#6366F1')
const creating = ref(false)

/** アクティブなタグのみ（論理削除済みを除外） */
const activeTags = computed<ActionMemoTag[]>(() =>
  store.tags.filter((tag) => !tag.deleted),
)

/** 選択済みタグのオブジェクト配列 */
const selectedTags = computed<ActionMemoTag[]>(() =>
  props.modelValue
    .map((id) => store.tags.find((t) => t.id === id))
    .filter((t): t is ActionMemoTag => t !== undefined),
)

/** インクリメンタルサーチ結果（選択済みを除外） */
const filteredTags = computed<ActionMemoTag[]>(() => {
  const query = searchQuery.value.toLowerCase().trim()
  return activeTags.value.filter((tag) => {
    if (props.modelValue.includes(tag.id)) return false
    if (query.length === 0) return true
    return tag.name.toLowerCase().includes(query)
  })
})

const canAddMore = computed(() => props.modelValue.length < MAX_TAGS_PER_MEMO)
const canCreateTag = computed(() => activeTags.value.length < MAX_TAGS_TOTAL)

function selectTag(tag: ActionMemoTag) {
  if (!canAddMore.value) return
  emit('update:modelValue', [...props.modelValue, tag.id])
  searchQuery.value = ''
  showDropdown.value = false
}

function removeTag(tagId: number) {
  emit('update:modelValue', props.modelValue.filter((id) => id !== tagId))
}

async function createAndSelect() {
  if (!newTagName.value.trim() || creating.value) return
  creating.value = true
  try {
    const payload: CreateTagPayload = {
      name: newTagName.value.trim(),
      color: newTagColor.value || undefined,
    }
    const created = await store.createTag(payload)
    if (created && canAddMore.value) {
      emit('update:modelValue', [...props.modelValue, created.id])
    }
    newTagName.value = ''
    showCreateForm.value = false
    showDropdown.value = false
  } finally {
    creating.value = false
  }
}

function onInputFocus() {
  showDropdown.value = true
}

function onInputBlur() {
  // 遅延して閉じる（ドロップダウン内のクリックを拾うため）
  setTimeout(() => {
    showDropdown.value = false
  }, 200)
}

// マウント時にタグ一覧を取得
onMounted(async () => {
  if (store.tags.length === 0) {
    await store.fetchTags()
  }
})
</script>

<template>
  <div class="flex flex-col gap-1.5" data-testid="tag-picker">
    <!-- 選択済みタグのチップ表示 -->
    <div v-if="selectedTags.length > 0" class="flex flex-wrap gap-1">
      <span
        v-for="tag in selectedTags"
        :key="tag.id"
        class="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium"
        :style="tag.color ? { backgroundColor: tag.color, color: '#fff' } : {}"
        :class="!tag.color ? 'bg-surface-100 text-surface-700 dark:bg-surface-700 dark:text-surface-200' : ''"
        :data-testid="`tag-chip-${tag.id}`"
      >
        #{{ tag.name }}
        <button
          type="button"
          class="ml-0.5 rounded-full p-0.5 hover:bg-black/10"
          :aria-label="`${t('action_memo.tag.delete')} ${tag.name}`"
          :data-testid="`tag-chip-remove-${tag.id}`"
          @click.stop="removeTag(tag.id)"
        >
          <i class="pi pi-times text-[0.6rem]" />
        </button>
      </span>
    </div>

    <!-- 上限警告 -->
    <p
      v-if="!canAddMore"
      class="text-xs text-amber-600 dark:text-amber-400"
      data-testid="tag-picker-max-reached"
    >
      {{ t('action_memo.tag.max_reached') }}
    </p>

    <!-- 検索入力 -->
    <div v-if="canAddMore" class="relative">
      <input
        v-model="searchQuery"
        type="text"
        class="w-full rounded-lg border border-surface-200 bg-transparent px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700"
        :placeholder="t('action_memo.tag.search_placeholder')"
        data-testid="tag-picker-search"
        @focus="onInputFocus"
        @blur="onInputBlur"
      >

      <!-- ドロップダウン候補 -->
      <div
        v-if="showDropdown && (filteredTags.length > 0 || searchQuery.trim().length > 0)"
        class="absolute z-10 mt-1 max-h-48 w-full overflow-y-auto rounded-lg border border-surface-200 bg-surface-0 shadow-lg dark:border-surface-700 dark:bg-surface-800"
        data-testid="tag-picker-dropdown"
      >
        <button
          v-for="tag in filteredTags"
          :key="tag.id"
          type="button"
          class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-surface-100 dark:hover:bg-surface-700"
          :data-testid="`tag-option-${tag.id}`"
          @mousedown.prevent="selectTag(tag)"
        >
          <span
            class="inline-block h-3 w-3 rounded-full"
            :style="{ backgroundColor: tag.color ?? '#94a3b8' }"
          />
          <span>{{ tag.name }}</span>
        </button>

        <!-- 新規作成ボタン -->
        <button
          v-if="canCreateTag"
          type="button"
          class="flex w-full items-center gap-2 border-t border-surface-200 px-3 py-2 text-left text-sm text-primary hover:bg-surface-100 dark:border-surface-700 dark:hover:bg-surface-700"
          data-testid="tag-picker-create-button"
          @mousedown.prevent="showCreateForm = true; showDropdown = false"
        >
          <i class="pi pi-plus text-xs" />
          <span>{{ t('action_memo.tag.create_button') }}</span>
        </button>

        <p
          v-if="!canCreateTag"
          class="px-3 py-2 text-xs text-surface-500"
        >
          {{ t('action_memo.tag.limit_reached') }}
        </p>
      </div>
    </div>

    <!-- インライン新規作成フォーム -->
    <div
      v-if="showCreateForm"
      class="flex flex-col gap-2 rounded-lg border border-primary/30 bg-primary/5 p-2 dark:bg-primary/10"
      data-testid="tag-picker-create-form"
    >
      <div class="flex items-center gap-2">
        <input
          v-model="newTagName"
          type="text"
          maxlength="50"
          class="flex-1 rounded border border-surface-200 bg-transparent px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-primary dark:border-surface-700"
          :placeholder="t('action_memo.tag.name_placeholder')"
          data-testid="tag-picker-new-name"
        >
        <label class="flex items-center gap-1 text-xs text-surface-600 dark:text-surface-300">
          {{ t('action_memo.tag.color_label') }}
          <input
            v-model="newTagColor"
            type="color"
            class="h-6 w-6 cursor-pointer rounded border-0"
            data-testid="tag-picker-new-color"
          >
        </label>
      </div>
      <div class="flex items-center justify-end gap-2">
        <button
          type="button"
          class="rounded px-3 py-1 text-xs text-surface-600 hover:bg-surface-100 dark:text-surface-300 dark:hover:bg-surface-700"
          data-testid="tag-picker-create-cancel"
          @click="showCreateForm = false; newTagName = ''"
        >
          {{ t('action_memo.tag.cancel') }}
        </button>
        <button
          type="button"
          class="rounded bg-primary px-3 py-1 text-xs font-medium text-white disabled:opacity-50"
          :disabled="!newTagName.trim() || creating"
          data-testid="tag-picker-create-submit"
          @click="createAndSelect"
        >
          {{ t('action_memo.tag.create_button') }}
        </button>
      </div>
    </div>
  </div>
</template>
