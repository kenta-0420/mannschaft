<script setup lang="ts">
import type { KbPageResponse, KbPageSummaryResponse, KbAccessLevel } from '~/types/knowledgeBase'
import type { KbScopeType } from '~/composables/useKnowledgeBaseApi'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  scopeType: KbScopeType
  scopeId: number
  editPage?: KbPageResponse | null
  pages?: KbPageSummaryResponse[]
}>()

const emit = defineEmits<{
  saved: []
}>()

const { createPage, updatePage } = useKnowledgeBaseApi(props.scopeType)
const { success: showSuccess, error: showError } = useNotification()

const title = ref('')
const body = ref('')
const icon = ref('')
const accessLevel = ref<KbAccessLevel>('ALL_MEMBERS')
const parentId = ref<number | undefined>(undefined)
const submitting = ref(false)

const isEditing = computed(() => !!props.editPage)
const dialogTitle = computed(() => isEditing.value ? 'ページを編集' : '新規ページ')

const accessLevelOptions = [
  { label: '全メンバー', value: 'ALL_MEMBERS' },
  { label: '管理者のみ', value: 'ADMIN_ONLY' },
  { label: 'カスタム', value: 'CUSTOM' },
]

const parentOptions = computed(() => {
  const opts: { label: string; value: number | undefined }[] = [
    { label: 'なし (ルート)', value: undefined },
  ]
  if (props.pages) {
    for (const p of props.pages) {
      // Exclude the editing page itself and its children from parent options
      if (props.editPage && (p.id === props.editPage.id || p.path.startsWith(props.editPage.path + '/'))) {
        continue
      }
      const indent = '\u00A0\u00A0'.repeat(p.depth)
      opts.push({
        label: `${indent}${p.icon || ''} ${p.title}`.trim(),
        value: p.id,
      })
    }
  }
  return opts
})

function resetForm() {
  title.value = ''
  body.value = ''
  icon.value = ''
  accessLevel.value = 'ALL_MEMBERS'
  parentId.value = undefined
}

function populateFromEditPage() {
  if (props.editPage) {
    title.value = props.editPage.title
    body.value = props.editPage.body || ''
    icon.value = props.editPage.icon || ''
    accessLevel.value = props.editPage.accessLevel
    parentId.value = props.editPage.parentId ?? undefined
  }
}

async function onSubmit() {
  if (!title.value.trim() || submitting.value) return
  submitting.value = true
  try {
    if (isEditing.value && props.editPage) {
      await updatePage(props.scopeId, props.editPage.id, {
        title: title.value.trim(),
        body: body.value || undefined,
        icon: icon.value || undefined,
        accessLevel: accessLevel.value,
        version: props.editPage.version,
      })
      showSuccess('ページを更新しました')
    } else {
      await createPage(props.scopeId, {
        title: title.value.trim(),
        body: body.value || undefined,
        icon: icon.value || undefined,
        accessLevel: accessLevel.value,
        parentId: parentId.value,
      })
      showSuccess('ページを作成しました')
    }
    visible.value = false
    resetForm()
    emit('saved')
  } catch {
    showError(isEditing.value ? '更新に失敗しました' : '作成に失敗しました')
  } finally {
    submitting.value = false
  }
}

watch(visible, (v) => {
  if (v) {
    if (props.editPage) {
      populateFromEditPage()
    } else {
      resetForm()
    }
  }
})
</script>

<template>
  <Dialog v-model:visible="visible" :header="dialogTitle" modal class="w-full max-w-2xl">
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">タイトル <span class="text-red-500">*</span></label>
        <InputText v-model="title" class="w-full" placeholder="ページのタイトル" />
      </div>

      <div class="flex gap-4">
        <div class="w-24">
          <label class="mb-1 block text-sm font-medium">アイコン</label>
          <InputText v-model="icon" class="w-full" placeholder="📄" />
        </div>
        <div class="flex-1">
          <label class="mb-1 block text-sm font-medium">アクセスレベル</label>
          <Select
            v-model="accessLevel"
            :options="accessLevelOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
          <Message v-if="accessLevel === 'CUSTOM'" severity="info" class="mt-2">
            カスタムACLは現在準備中です。選択中は「管理者のみ」と同等の制限が適用されます。
          </Message>
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">親ページ</label>
        <Select
          v-model="parentId"
          :options="parentOptions"
          option-label="label"
          option-value="value"
          placeholder="なし (ルート)"
          class="w-full"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">本文</label>
        <Textarea v-model="body" auto-resize rows="12" class="w-full" placeholder="ページの内容を入力..." />
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button
        :label="isEditing ? '更新' : '作成'"
        :loading="submitting"
        :disabled="!title.trim()"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
