<script setup lang="ts">
import type { MemberProfileField } from '~/types/member-profile'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const memberProfileApi = useMemberProfileApi()
const notification = useNotification()

const fields = ref<MemberProfileField[]>([])
const loading = ref(false)
const showDialog = ref(false)
const editingField = ref<MemberProfileField | null>(null)
const confirmDeleteId = ref<number | null>(null)
const showDeleteDialog = ref(false)

const fieldTypeOptions = [
  { label: 'テキスト', value: 'TEXT' },
  { label: '数値', value: 'NUMBER' },
  { label: '日付', value: 'DATE' },
  { label: '選択肢', value: 'SELECT' },
  { label: 'チェックボックス', value: 'CHECKBOX' },
  { label: '複数行', value: 'TEXTAREA' },
]

const fieldTypeLabel: Record<string, string> = {
  TEXT: 'テキスト',
  NUMBER: '数値',
  DATE: '日付',
  SELECT: '選択肢',
  CHECKBOX: 'チェックボックス',
  TEXTAREA: '複数行',
}

const form = ref({
  fieldName: '',
  fieldType: 'TEXT',
  isRequired: false,
  optionsText: '',
})

function toApiScopeType(scopeType: 'TEAM' | 'ORGANIZATION'): 'team' | 'organization' {
  return scopeType === 'TEAM' ? 'team' : 'organization'
}

async function loadFields() {
  loading.value = true
  try {
    fields.value = await memberProfileApi.listFields(toApiScopeType(props.scopeType), props.scopeId)
  } catch {
    notification.error('フィールド一覧の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingField.value = null
  form.value = { fieldName: '', fieldType: 'TEXT', isRequired: false, optionsText: '' }
  showDialog.value = true
}

function openEdit(field: MemberProfileField) {
  // TODO: updateField が useMemberProfileApi に存在しないため編集機能は未実装
  editingField.value = field
  form.value = {
    fieldName: field.fieldName,
    fieldType: field.fieldType,
    isRequired: field.isRequired,
    optionsText: field.options ? field.options.join('\n') : '',
  }
  showDialog.value = true
}

async function save() {
  if (!form.value.fieldName.trim()) return
  try {
    const options =
      form.value.fieldType === 'SELECT'
        ? form.value.optionsText
            .split('\n')
            .map((s) => s.trim())
            .filter((s) => s.length > 0)
        : undefined

    if (editingField.value) {
      // TODO: updateField が useMemberProfileApi に存在しないため更新は未実装
      // await memberProfileApi.updateField(editingField.value.id, { fieldName: form.value.fieldName, fieldType: form.value.fieldType, options, isRequired: form.value.isRequired })
      notification.error('フィールドの更新機能は未実装です（updateField APIが必要）')
      return
    } else {
      await memberProfileApi.createField(toApiScopeType(props.scopeType), props.scopeId, {
        fieldName: form.value.fieldName,
        fieldType: form.value.fieldType,
        options,
        isRequired: form.value.isRequired,
      })
      notification.success('フィールドを追加しました')
    }

    showDialog.value = false
    await loadFields()
  } catch {
    notification.error('保存に失敗しました')
  }
}

function confirmDelete(id: number) {
  confirmDeleteId.value = id
  showDeleteDialog.value = true
}

async function executeDelete() {
  if (confirmDeleteId.value === null) return
  try {
    // TODO: deleteField が useMemberProfileApi に存在しないため削除は未実装
    // await memberProfileApi.deleteField(confirmDeleteId.value)
    notification.error('フィールドの削除機能は未実装です（deleteField APIが必要）')
  } finally {
    showDeleteDialog.value = false
    confirmDeleteId.value = null
  }
}

onMounted(loadFields)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <span class="text-sm text-gray-500">{{ fields.length }} 件のフィールド</span>
      <Button label="フィールドを追加" icon="pi pi-plus" @click="openCreate" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="fields" class="w-full">
      <Column field="fieldName" header="フィールド名" />
      <Column header="型">
        <template #body="{ data }">
          {{ fieldTypeLabel[data.fieldType] ?? data.fieldType }}
        </template>
      </Column>
      <Column header="必須">
        <template #body="{ data }">
          <span v-if="data.isRequired" class="text-red-500 font-bold">必須</span>
          <span v-else class="text-gray-400">任意</span>
        </template>
      </Column>
      <Column header="操作">
        <template #body="{ data }">
          <div class="flex gap-2">
            <Button
              icon="pi pi-pencil"
              text
              rounded
              severity="secondary"
              @click="openEdit(data)"
            />
            <Button
              icon="pi pi-trash"
              text
              rounded
              severity="danger"
              @click="confirmDelete(data.id)"
            />
          </div>
        </template>
      </Column>
    </DataTable>

    <!-- フィールド作成・編集ダイアログ -->
    <Dialog
      v-model:visible="showDialog"
      :header="editingField ? 'フィールド編集' : 'フィールドを追加'"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">フィールド名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.fieldName" class="w-full" placeholder="例: 身長" />
        </div>

        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">型</label>
          <Select
            v-model="form.fieldType"
            :options="fieldTypeOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isRequired" input-id="field-required" :binary="true" />
          <label for="field-required" class="text-sm font-medium">必須項目にする</label>
        </div>

        <div v-if="form.fieldType === 'SELECT'" class="flex flex-col gap-1">
          <label class="text-sm font-medium">選択肢（1行に1項目）</label>
          <Textarea
            v-model="form.optionsText"
            class="w-full"
            rows="4"
            placeholder="例:&#10;選択肢A&#10;選択肢B&#10;選択肢C"
          />
        </div>
      </div>

      <template #footer>
        <Button label="キャンセル" severity="secondary" @click="showDialog = false" />
        <Button
          :label="editingField ? '更新' : '追加'"
          icon="pi pi-check"
          :disabled="!form.fieldName.trim()"
          @click="save"
        />
      </template>
    </Dialog>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="showDeleteDialog"
      header="フィールドの削除"
      :modal="true"
      class="w-full max-w-sm"
    >
      <p>このフィールドを削除しますか？この操作は元に戻せません。</p>
      <template #footer>
        <Button label="キャンセル" severity="secondary" @click="showDeleteDialog = false" />
        <Button label="削除" severity="danger" icon="pi pi-trash" @click="executeDelete" />
      </template>
    </Dialog>
  </div>
</template>
