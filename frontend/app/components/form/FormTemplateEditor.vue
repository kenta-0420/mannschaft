<script setup lang="ts">
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'
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
const serverErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.templateId)

const schema = toTypedSchema(
  z.object({
    name: z.string().min(1, 'フォーム名は必須です'),
    description: z.string().default(''),
    requiresApproval: z.boolean().default(false),
    allowEditAfterSubmit: z.boolean().default(false),
    maxSubmissionsPerUser: z.number().min(1, '1以上の値を入力してください').nullable().default(null),
    deadline: z.date().nullable().default(null),
    targetCount: z.number().min(1, '1以上の値を入力してください').nullable().default(null),
  }),
)

const { defineField, handleSubmit, errors, resetForm: resetValidation, setValues } = useForm({
  validationSchema: schema,
  initialValues: {
    name: '',
    description: '',
    requiresApproval: false,
    allowEditAfterSubmit: false,
    maxSubmissionsPerUser: null,
    deadline: null,
    targetCount: null,
  },
})

const [name] = defineField('name')
const [description] = defineField('description')
const [requiresApproval] = defineField('requiresApproval')
const [allowEditAfterSubmit] = defineField('allowEditAfterSubmit')
const [maxSubmissionsPerUser] = defineField('maxSubmissionsPerUser')
const [deadline] = defineField('deadline')
const [targetCount] = defineField('targetCount')

const fields = ref<FormFieldRequest[]>([])

const fieldTypeOptions = [
  { label: 'テキスト', value: 'TEXT' },
  { label: '数値', value: 'NUMBER' },
  { label: '日付', value: 'DATE' },
  { label: 'メール', value: 'EMAIL' },
  { label: '電話番号', value: 'PHONE' },
  { label: 'ドロップダウン', value: 'DROPDOWN' },
  { label: 'ラジオボタン', value: 'RADIO' },
  { label: 'チェックボックス', value: 'CHECKBOX' },
  { label: '複数行テキスト', value: 'TEXTAREA' },
  { label: 'ファイル添付', value: 'FILE' },
  { label: '手書き署名', value: 'SIGNATURE' },
  { label: 'セクション見出し', value: 'SECTION' },
  { label: '説明文', value: 'DESCRIPTION' },
]

const autoFillKeyOptions = [
  { label: 'なし', value: '' },
  { label: '氏名', value: 'member_name' },
  { label: 'メールアドレス', value: 'member_email' },
  { label: '会員番号', value: 'member_number' },
  { label: '電話番号', value: 'member_phone' },
  { label: 'チーム名', value: 'team_name' },
  { label: '組織名', value: 'org_name' },
  { label: '今日の日付', value: 'current_date' },
]

function supportsAutoFill(fieldType: string) {
  return ['TEXT', 'EMAIL', 'PHONE'].includes(fieldType)
}

function supportsOptions(fieldType: string) {
  return ['DROPDOWN', 'RADIO', 'CHECKBOX'].includes(fieldType)
}

function supportsRequired(fieldType: string) {
  return !['SECTION', 'DESCRIPTION'].includes(fieldType)
}

watch(
  () => [props.visible, props.templateId],
  async ([visible, templateId]) => {
    if (visible && templateId) {
      try {
        const res = await formApi.getTemplate(props.scopeType, props.scopeId, templateId as number)
        const d = res.data
        setValues({
          name: d.name,
          description: d.description ?? '',
          requiresApproval: d.requiresApproval,
          allowEditAfterSubmit: d.allowEditAfterSubmit,
          maxSubmissionsPerUser: d.maxSubmissionsPerUser,
          deadline: d.deadline ? new Date(d.deadline) : null,
          targetCount: d.targetCount,
        })
        fields.value = d.fields.map((f) => ({
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
  if (fields.value.length >= 30) {
    notification.warn('フィールドは最大30件まで追加できます')
    return
  }
  fields.value.push({
    fieldKey: `field_${fields.value.length + 1}`,
    fieldLabel: '',
    fieldType: 'TEXT',
    isRequired: false,
    sortOrder: fields.value.length,
    placeholder: '',
    autoFillKey: '',
    optionsJson: '',
  })
}

function removeField(index: number) {
  fields.value.splice(index, 1)
  fields.value.forEach((f, i) => {
    f.sortOrder = i
  })
}

const submit = handleSubmit(async (values) => {
  submitting.value = true
  serverErrors.value = {}

  const body = {
    name: values.name.trim(),
    description: values.description.trim() || undefined,
    requiresApproval: values.requiresApproval,
    allowEditAfterSubmit: values.allowEditAfterSubmit,
    maxSubmissionsPerUser: values.maxSubmissionsPerUser || undefined,
    deadline: values.deadline ? values.deadline.toISOString() : undefined,
    targetCount: values.targetCount || undefined,
    fields: fields.value.map((f) => ({
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
    serverErrors.value = getFieldErrors(error)
    if (Object.keys(serverErrors.value).length === 0) {
      handleApiError(error, isEdit.value ? 'フォームテンプレート更新' : 'フォームテンプレート作成')
    }
  } finally {
    submitting.value = false
  }
})

function resetForm() {
  resetValidation({
    values: {
      name: '',
      description: '',
      requiresApproval: false,
      allowEditAfterSubmit: false,
      maxSubmissionsPerUser: null,
      deadline: null,
      targetCount: null,
    },
  })
  fields.value = []
  serverErrors.value = {}
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
          v-model="name"
          class="w-full"
          :class="{ 'p-invalid': errors.name || serverErrors.name }"
          placeholder="フォーム名"
        />
        <small v-if="errors.name || serverErrors.name" class="text-red-500">{{ errors.name || serverErrors.name }}</small>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="description" rows="2" class="w-full" placeholder="フォームの説明" />
      </div>

      <div class="grid grid-cols-3 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">期限</label>
          <DatePicker
            v-model="deadline"
            show-time
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">目標提出数</label>
          <InputNumber v-model="targetCount" class="w-full" :min="1" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">最大提出回数/人</label>
          <InputNumber v-model="maxSubmissionsPerUser" class="w-full" :min="1" />
        </div>
      </div>

      <div class="flex gap-4">
        <div class="flex items-center gap-2">
          <Checkbox v-model="requiresApproval" :binary="true" input-id="reqApproval" />
          <label for="reqApproval" class="text-sm">承認制</label>
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="allowEditAfterSubmit" :binary="true" input-id="allowEdit" />
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
        <div v-for="(field, index) in fields" :key="index" class="mb-2 rounded border p-3">
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
          <div v-if="supportsRequired(field.fieldType ?? '')" class="mt-2 flex items-center gap-4">
            <div class="flex items-center gap-2">
              <Checkbox v-model="field.isRequired" :binary="true" :input-id="`reqField${index}`" />
              <label :for="`reqField${index}`" class="text-xs">必須</label>
            </div>
            <InputText v-model="field.placeholder" class="flex-1" placeholder="プレースホルダー" />
          </div>
          <!-- autoFillKey: TEXT/EMAIL/PHONEのみ表示 -->
          <div v-if="supportsAutoFill(field.fieldType ?? '')" class="mt-2">
            <label class="mb-1 block text-xs font-medium">自動入力キー（任意）</label>
            <Select
              v-model="field.autoFillKey"
              :options="autoFillKeyOptions"
              option-label="label"
              option-value="value"
              class="w-full"
              placeholder="なし"
            />
          </div>
          <!-- optionsJson: DROPDOWN/RADIO/CHECKBOXのみ表示 -->
          <div v-if="supportsOptions(field.fieldType ?? '')" class="mt-2">
            <label class="mb-1 block text-xs font-medium">選択肢（JSON配列 例: ["A","B","C"]）</label>
            <InputText
              v-model="field.optionsJson"
              class="w-full"
              placeholder='["選択肢1","選択肢2","選択肢3"]'
            />
          </div>
        </div>
        <p v-if="fields.length === 0" class="text-sm text-surface-400">
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
