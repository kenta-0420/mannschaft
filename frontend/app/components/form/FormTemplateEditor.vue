<script setup lang="ts">
import type { FormFieldRequest } from '~/types/form'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  templateId?: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const formApi = useFormApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.templateId)

const form = ref({
  name: '',
  description: '',
  requiresApproval: false,
  allowEditAfterSubmit: false,
  maxSubmissionsPerUser: null as number | null,
  deadline: null as Date | null,
  targetCount: null as number | null,
  fields: [] as FormFieldRequest[],
})

const fieldTypeOptions = [
  { label: 'テキスト', value: 'TEXT' },
  { label: 'テキストエリア', value: 'TEXTAREA' },
  { label: '数値', value: 'NUMBER' },
  { label: '日付', value: 'DATE' },
  { label: '選択', value: 'SELECT' },
  { label: 'チェックボックス', value: 'CHECKBOX' },
  { label: 'ファイル', value: 'FILE' },
]

watch(
  () => [props.visible, props.templateId],
  async ([visible, templateId]) => {
    if (visible && templateId) {
      try {
        const res = await formApi.getTemplate(props.scopeType, props.scopeId, templateId as number)
        const d = res.data
        form.value.name = d.name
        form.value.description = d.description ?? ''
        form.value.requiresApproval = d.requiresApproval
        form.value.allowEditAfterSubmit = d.allowEditAfterSubmit
        form.value.maxSubmissionsPerUser = d.maxSubmissionsPerUser
        form.value.deadline = d.deadline ? new Date(d.deadline) : null
        form.value.targetCount = d.targetCount
        form.value.fields = d.fields.map((f) => ({
          fieldKey: f.fieldKey,
          fieldLabel: f.fieldLabel ?? '',
          fieldType: f.fieldType ?? 'TEXT',
          isRequired: f.isRequired,
          sortOrder: f.sortOrder,
          placeholder: f.placeholder ?? '',
          optionsJson: f.optionsJson ?? '',
        }))
      } catch {
        notification.error('テンプレート情報の取得に失敗しました')
      }
    } else if (visible && !templateId) {
      resetForm()
    }
  },
)

function addField() {
  form.value.fields.push({
    fieldKey: `field_${form.value.fields.length + 1}`,
    fieldLabel: '',
    fieldType: 'TEXT',
    isRequired: false,
    sortOrder: form.value.fields.length,
    placeholder: '',
  })
}

function removeField(index: number) {
  form.value.fields.splice(index, 1)
  form.value.fields.forEach((f, i) => {
    f.sortOrder = i
  })
}

async function submit() {
  if (!form.value.name.trim()) {
    fieldErrors.value = { name: 'フォーム名は必須です' }
    return
  }

  submitting.value = true
  fieldErrors.value = {}

  const body = {
    name: form.value.name.trim(),
    description: form.value.description.trim() || undefined,
    requiresApproval: form.value.requiresApproval,
    allowEditAfterSubmit: form.value.allowEditAfterSubmit,
    maxSubmissionsPerUser: form.value.maxSubmissionsPerUser || undefined,
    deadline: form.value.deadline ? form.value.deadline.toISOString() : undefined,
    targetCount: form.value.targetCount || undefined,
    fields: form.value.fields.map((f) => ({
      ...f,
      optionsJson: f.optionsJson || undefined,
      placeholder: f.placeholder || undefined,
    })),
  }

  try {
    if (isEdit.value && props.templateId) {
      await formApi.updateTemplate(props.scopeType, props.scopeId, props.templateId, body)
      notification.success('テンプレートを更新しました')
    } else {
      await formApi.createTemplate(props.scopeType, props.scopeId, body)
      notification.success('テンプレートを作成しました')
    }
    emit('saved')
    close()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0) {
      handleApiError(error, isEdit.value ? 'フォームテンプレート更新' : 'フォームテンプレート作成')
    }
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    name: '',
    description: '',
    requiresApproval: false,
    allowEditAfterSubmit: false,
    maxSubmissionsPerUser: null,
    deadline: null,
    targetCount: null,
    fields: [],
  }
  fieldErrors.value = {}
}

function close() {
  emit('update:visible', false)
  resetForm()
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="isEdit ? 'フォームテンプレート編集' : 'フォームテンプレート作成'"
    :style="{ width: '700px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >フォーム名 <span class="text-red-500">*</span></label
        >
        <InputText
          v-model="form.name"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors.name }"
          placeholder="フォーム名"
        />
        <small v-if="fieldErrors.name" class="text-red-500">{{ fieldErrors.name }}</small>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="form.description" rows="2" class="w-full" placeholder="フォームの説明" />
      </div>

      <div class="grid grid-cols-3 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">期限</label>
          <DatePicker
            v-model="form.deadline"
            show-time
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">目標提出数</label>
          <InputNumber v-model="form.targetCount" class="w-full" :min="1" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">最大提出回数/人</label>
          <InputNumber v-model="form.maxSubmissionsPerUser" class="w-full" :min="1" />
        </div>
      </div>

      <div class="flex gap-4">
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.requiresApproval" :binary="true" input-id="reqApproval" />
          <label for="reqApproval" class="text-sm">承認制</label>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.allowEditAfterSubmit" :binary="true" input-id="allowEdit" />
          <label for="allowEdit" class="text-sm">提出後の編集を許可</label>
        </div>
      </div>

      <!-- フィールド定義 -->
      <div>
        <div class="mb-2 flex items-center justify-between">
          <label class="text-sm font-medium">フィールド</label>
          <Button
            label="フィールド追加"
            icon="pi pi-plus"
            size="small"
            outlined
            @click="addField"
          />
        </div>
        <div v-for="(field, index) in form.fields" :key="index" class="mb-2 rounded border p-3">
          <div class="mb-2 flex items-center justify-between">
            <span class="text-sm font-medium">フィールド {{ index + 1 }}</span>
            <Button
              icon="pi pi-trash"
              text
              rounded
              size="small"
              severity="danger"
              @click="removeField(index)"
            />
          </div>
          <div class="grid grid-cols-3 gap-2">
            <div>
              <InputText v-model="field.fieldKey" class="w-full" placeholder="フィールドキー" />
            </div>
            <div>
              <InputText v-model="field.fieldLabel" class="w-full" placeholder="ラベル" />
            </div>
            <div>
              <Select
                v-model="field.fieldType"
                :options="fieldTypeOptions"
                option-label="label"
                option-value="value"
                class="w-full"
              />
            </div>
          </div>
          <div class="mt-2 flex items-center gap-4">
            <div class="flex items-center gap-2">
              <Checkbox v-model="field.isRequired" :binary="true" :input-id="`reqField${index}`" />
              <label :for="`reqField${index}`" class="text-xs">必須</label>
            </div>
            <InputText v-model="field.placeholder" class="flex-1" placeholder="プレースホルダー" />
          </div>
        </div>
        <p v-if="form.fields.length === 0" class="text-sm text-surface-400">
          フィールドが追加されていません
        </p>
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button
        :label="isEdit ? '更新' : '作成'"
        icon="pi pi-check"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
