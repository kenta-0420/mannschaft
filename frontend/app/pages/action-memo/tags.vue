<script setup lang="ts">
import type { ActionMemoTag, CreateTagPayload, UpdateTagPayload } from '~/types/actionMemo'

/**
 * F02.5 タグ管理画面（Phase 4）。
 *
 * <p>設計書 §4.x に基づく。タグ一覧（名前・色）の表示、新規作成、
 * 編集（名前・色）、削除（確認ダイアログ付き）を提供する。
 * 復活機能は無し（§11 #9）。</p>
 */

definePageMeta({ middleware: 'auth' })

const router = useRouter()
const { t } = useI18n()
const store = useActionMemoStore()

// === 新規作成 ===
const showCreateForm = ref(false)
const newTagName = ref('')
const newTagColor = ref('#6366F1')
const creating = ref(false)

// === 編集 ===
const editingTagId = ref<number | null>(null)
const editName = ref('')
const editColor = ref('')

// === 削除確認 ===
const deleteConfirmTagId = ref<number | null>(null)

const activeTags = computed<ActionMemoTag[]>(() =>
  store.tags.filter((tag) => !tag.deleted),
)

const canCreateTag = computed(() => activeTags.value.length < 100)

onMounted(async () => {
  await store.fetchTags()
})

async function createTag() {
  if (!newTagName.value.trim() || creating.value) return
  creating.value = true
  try {
    const payload: CreateTagPayload = {
      name: newTagName.value.trim(),
      color: newTagColor.value || undefined,
    }
    await store.createTag(payload)
    newTagName.value = ''
    newTagColor.value = '#6366F1'
    showCreateForm.value = false
  } finally {
    creating.value = false
  }
}

function startEdit(tag: ActionMemoTag) {
  editingTagId.value = tag.id
  editName.value = tag.name
  editColor.value = tag.color ?? '#6366F1'
}

function cancelEdit() {
  editingTagId.value = null
  editName.value = ''
  editColor.value = ''
}

async function saveEdit() {
  if (editingTagId.value === null || !editName.value.trim()) return
  const payload: UpdateTagPayload = {
    name: editName.value.trim(),
    color: editColor.value || undefined,
  }
  const updated = await store.updateTag(editingTagId.value, payload)
  if (updated) {
    cancelEdit()
  }
}

function confirmDelete(tagId: number) {
  deleteConfirmTagId.value = tagId
}

async function executeDelete() {
  if (deleteConfirmTagId.value === null) return
  await store.deleteTag(deleteConfirmTagId.value)
  deleteConfirmTagId.value = null
}

function goBack() {
  router.push('/action-memo')
}
</script>

<template>
  <div class="mx-auto flex max-w-2xl flex-col gap-4 px-3 py-4">
    <header class="flex items-center justify-between">
      <h1 class="text-xl font-bold" data-testid="tag-management-title">
        {{ t('action_memo.tag.list_title') }}
      </h1>
      <button
        type="button"
        class="rounded-lg px-3 py-1 text-sm text-primary hover:bg-primary/10"
        data-testid="tag-management-back"
        @click="goBack"
      >
        <i class="pi pi-arrow-left mr-1 text-xs" />
        {{ t('action_memo.page.back_to_memo') }}
      </button>
    </header>

    <!-- エラーバナー -->
    <div
      v-if="store.error"
      class="rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-900/30 dark:text-rose-200"
      role="alert"
    >
      {{ t(store.error) }}
    </div>

    <!-- 新規作成ボタン / フォーム -->
    <div v-if="!showCreateForm">
      <button
        v-if="canCreateTag"
        type="button"
        class="flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary/90"
        data-testid="tag-create-button"
        @click="showCreateForm = true"
      >
        <i class="pi pi-plus text-xs" />
        {{ t('action_memo.tag.create_button') }}
      </button>
      <p v-else class="text-sm text-amber-600 dark:text-amber-400">
        {{ t('action_memo.tag.limit_reached') }}
      </p>
    </div>

    <div
      v-if="showCreateForm"
      class="flex flex-col gap-2 rounded-xl border border-primary/30 bg-primary/5 p-3 dark:bg-primary/10"
      data-testid="tag-create-form"
    >
      <div class="flex items-center gap-3">
        <input
          v-model="newTagName"
          type="text"
          maxlength="50"
          class="flex-1 rounded-lg border border-surface-200 bg-transparent px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-700"
          :placeholder="t('action_memo.tag.name_placeholder')"
          data-testid="tag-create-name"
        >
        <label class="flex items-center gap-1.5 text-xs text-surface-600 dark:text-surface-300">
          {{ t('action_memo.tag.color_label') }}
          <input
            v-model="newTagColor"
            type="color"
            class="h-8 w-8 cursor-pointer rounded border-0"
            data-testid="tag-create-color"
          >
        </label>
      </div>
      <div class="flex items-center justify-end gap-2">
        <button
          type="button"
          class="rounded-lg px-4 py-1.5 text-sm text-surface-600 hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700"
          data-testid="tag-create-cancel"
          @click="showCreateForm = false; newTagName = ''"
        >
          {{ t('action_memo.tag.cancel') }}
        </button>
        <button
          type="button"
          class="rounded-lg bg-primary px-4 py-1.5 text-sm font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="!newTagName.trim() || creating"
          data-testid="tag-create-submit"
          @click="createTag"
        >
          {{ t('action_memo.tag.create_button') }}
        </button>
      </div>
    </div>

    <!-- タグ一覧 -->
    <section class="flex flex-col gap-2">
      <p
        v-if="activeTags.length === 0"
        class="py-8 text-center text-sm text-surface-500 dark:text-surface-400"
        data-testid="tag-list-empty"
      >
        {{ t('action_memo.tag.no_tags') }}
      </p>

      <div
        v-for="tag in activeTags"
        :key="tag.id"
        class="flex items-center gap-3 rounded-xl border border-surface-200 bg-surface-0 p-3 dark:border-surface-700 dark:bg-surface-800"
        :data-testid="`tag-item-${tag.id}`"
      >
        <!-- 通常表示 -->
        <template v-if="editingTagId !== tag.id">
          <span
            class="inline-block h-5 w-5 rounded-full"
            :style="{ backgroundColor: tag.color ?? '#94a3b8' }"
          />
          <span class="flex-1 text-sm font-medium text-surface-800 dark:text-surface-100">
            {{ tag.name }}
          </span>
          <button
            type="button"
            class="rounded p-1.5 text-surface-400 hover:bg-surface-100 hover:text-surface-700 dark:hover:bg-surface-700"
            :title="t('action_memo.tag.edit')"
            :data-testid="`tag-edit-${tag.id}`"
            @click="startEdit(tag)"
          >
            <i class="pi pi-pencil text-xs" />
          </button>
          <button
            type="button"
            class="rounded p-1.5 text-surface-400 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-900/40"
            :title="t('action_memo.tag.delete')"
            :data-testid="`tag-delete-${tag.id}`"
            @click="confirmDelete(tag.id)"
          >
            <i class="pi pi-trash text-xs" />
          </button>
        </template>

        <!-- 編集モード -->
        <template v-else>
          <input
            v-model="editColor"
            type="color"
            class="h-6 w-6 cursor-pointer rounded border-0"
            :data-testid="`tag-edit-color-${tag.id}`"
          >
          <input
            v-model="editName"
            type="text"
            maxlength="50"
            class="flex-1 rounded border border-surface-200 bg-transparent px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-primary dark:border-surface-700"
            :data-testid="`tag-edit-name-${tag.id}`"
          >
          <button
            type="button"
            class="rounded bg-primary px-3 py-1 text-xs font-medium text-white"
            :disabled="!editName.trim()"
            :data-testid="`tag-edit-save-${tag.id}`"
            @click="saveEdit"
          >
            {{ t('action_memo.tag.save') }}
          </button>
          <button
            type="button"
            class="rounded px-3 py-1 text-xs text-surface-600 hover:bg-surface-100 dark:text-surface-300"
            :data-testid="`tag-edit-cancel-${tag.id}`"
            @click="cancelEdit"
          >
            {{ t('action_memo.tag.cancel') }}
          </button>
        </template>
      </div>
    </section>

    <!-- 削除確認ダイアログ -->
    <Teleport to="body">
      <div
        v-if="deleteConfirmTagId !== null"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 px-4"
        role="dialog"
        aria-modal="true"
        data-testid="tag-delete-confirm-dialog"
        @click.self="deleteConfirmTagId = null"
      >
        <div
          class="flex w-full max-w-sm flex-col gap-4 rounded-2xl border border-surface-200 bg-surface-0 p-4 shadow-xl dark:border-surface-700 dark:bg-surface-800"
          @click.stop
        >
          <p class="text-sm text-surface-700 dark:text-surface-200">
            {{ t('action_memo.tag.delete_confirm') }}
          </p>
          <div class="flex items-center justify-end gap-2">
            <button
              type="button"
              class="rounded-lg px-4 py-1.5 text-sm text-surface-600 hover:bg-surface-100 dark:text-surface-200 dark:hover:bg-surface-700"
              data-testid="tag-delete-cancel"
              @click="deleteConfirmTagId = null"
            >
              {{ t('action_memo.tag.cancel') }}
            </button>
            <button
              type="button"
              class="rounded-lg bg-rose-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-rose-700"
              data-testid="tag-delete-confirm"
              @click="executeDelete"
            >
              {{ t('action_memo.tag.delete') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
