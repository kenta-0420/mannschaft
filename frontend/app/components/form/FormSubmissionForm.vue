<script setup lang="ts">
import { z } from 'zod'
import type { FormFieldResponse, FormTemplateResponse, SubmissionValueRequest } from '~/types/form'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  templateId: number
  submissionId?: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const formApi = useFormApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const template = ref<FormTemplateResponse | null>(null)
const submitting = ref(false)
const loading = ref(false)
const submitImmediately = ref(false)
const fieldErrors = ref<Record<string, string>>({})

// フィールド値
const fieldValues = ref<
  Record<string, { textValue: string; numberValue: number | null; dateValue: Date | null }>
>({})

const isEdit = computed(() => !!props.submissionId)

function buildValidationSchema(fields: FormFieldResponse[]) {
  const shape: Record<string, z.ZodTypeAny> = {}
  for (const field of fields) {
    const label = field.fieldLabel || field.fieldKey
    if (field.fieldType === 'NUMBER') {
      shape[field.fieldKey] = field.isRequired
        ? z.number({ required_error: `${label}は必須です` }).min(0, `${label}は必須です`)
        : z.number().nullable()
    } else if (field.fieldType === 'DATE') {
      shape[field.fieldKey] = field.isRequired
        ? z.date({ required_error: `${label}は必須です` })
        : z.date().nullable()
    } else {
      shape[field.fieldKey] = field.isRequired
        ? z.string().min(1, `${label}は必須です`)
        : z.string()
    }
  }
  return z.object(shape)
}

function validateFields(): boolean {
  if (!template.value) return false
  fieldErrors.value = {}

  const schema = buildValidationSchema(template.value.fields)
  const data: Record<string, string | number | Date | null> = {}
  for (const field of template.value.fields) {
    const val = fieldValues.value[field.fieldKey]
    if (field.fieldType === 'NUMBER') {
      data[field.fieldKey] = val?.numberValue ?? null
    } else if (field.fieldType === 'DATE') {
      data[field.fieldKey] = val?.dateValue ?? null
    } else {
      data[field.fieldKey] = val?.textValue ?? ''
    }
  }

  const result = schema.safeParse(data)
  if (!result.success) {
    for (const issue of result.error.issues) {
      const key = issue.path[0]
      if (typeof key === 'string') {
        fieldErrors.value[key] = issue.message
      }
    }
    return false
  }
  return true
}

watch(
  () => [props.visible, props.templateId],
  async ([visible]) => {
    if (visible) {
      loading.value = true
      try {
        const res = await formApi.getTemplate(props.scopeType, props.scopeId, props.templateId)
        template.value = res.data
        initFieldValues(res.data.fields)

        if (props.submissionId) {
          const subRes = await formApi.getSubmission(
            props.scopeType,
            props.scopeId,
            props.submissionId,
          )
          for (const val of subRes.data.values) {
            fieldValues.value[val.fieldKey] = {
              textValue: val.textValue ?? '',
              numberValue: val.numberValue ?? null,
              dateValue: val.dateValue ? new Date(val.dateValue) : null,
            }
          }
        }
      } catch {
        notification.error('テンプレート情報の取得に失敗しました')
      } finally {
        loading.value = false
      }
    }
  },
)

function initFieldValues(fields: FormFieldResponse[]) {
  fieldValues.value = {}
  fieldErrors.value = {}
  for (const field of fields) {
    fieldValues.value[field.fieldKey] = { textValue: '', numberValue: null, dateValue: null }
  }
}

function getFieldValue(fieldKey: string) {
  if (!fieldValues.value[fieldKey]) {
    fieldValues.value[fieldKey] = { textValue: '', numberValue: null, dateValue: null }
  }
  return fieldValues.value[fieldKey]!
}

async function submit() {
  if (!template.value) return
  if (!validateFields()) return

  submitting.value = true

  const values: SubmissionValueRequest[] = template.value.fields.map((field) => {
    const val = fieldValues.value[field.fieldKey] ?? { textValue: '', numberValue: null, dateValue: null }
    return {
      fieldKey: field.fieldKey,
      fieldType: field.fieldType ?? 'TEXT',
      textValue: val.textValue,
      numberValue: val.numberValue ?? undefined,
      dateValue: val.dateValue ? val.dateValue.toISOString().split('T')[0] : undefined,
    }
  })

  try {
    if (isEdit.value && props.submissionId) {
      await formApi.updateSubmission(props.scopeType, props.scopeId, props.submissionId, {
        submitImmediately: submitImmediately.value,
        values,
      })
      notification.success('回答を更新しました')
    } else {
      await formApi.createSubmission(props.scopeType, props.scopeId, {
        templateId: props.templateId,
        submitImmediately: submitImmediately.value,
        values,
      })
      notification.success('回答を提出しました')
    }
    emit('saved')
    close()
  } catch (error) {
    handleApiError(error, isEdit.value ? 'フォーム回答更新' : 'フォーム回答送信')
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:visible', false)
  fieldValues.value = {}
  fieldErrors.value = {}
  submitImmediately.value = false
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="isEdit ? '回答を編集' : '回答を提出'"
    :style="{ width: '600px' }"
    modal
    @update:visible="close"
  >
    <div v-if="loading" class="flex items-center justify-center py-8">
      <ProgressSpinner />
    </div>

    <div v-else-if="template" class="flex flex-col gap-4">
      <div
        v-if="template.description"
        class="rounded bg-surface-100 p-3 text-sm dark:bg-surface-800"
      >
        {{ template.description }}
      </div>

      <div v-for="field in template.fields" :key="field.id">
        <label class="mb-1 block text-sm font-medium">
          {{ field.fieldLabel || field.fieldKey }}
          <span v-if="field.isRequired" class="text-red-500">*</span>
        </label>

        <!-- テキスト -->
        <InputText
          v-if="field.fieldType === 'TEXT'"
          v-model="getFieldValue(field.fieldKey).textValue"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors[field.fieldKey] }"
          :placeholder="field.placeholder || ''"
        />

        <!-- テキストエリア -->
        <Textarea
          v-else-if="field.fieldType === 'TEXTAREA'"
          v-model="getFieldValue(field.fieldKey).textValue"
          rows="3"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors[field.fieldKey] }"
          :placeholder="field.placeholder || ''"
        />

        <!-- 数値 -->
        <InputNumber
          v-else-if="field.fieldType === 'NUMBER'"
          v-model="getFieldValue(field.fieldKey).numberValue"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors[field.fieldKey] }"
          :placeholder="field.placeholder || ''"
        />

        <!-- 日付 -->
        <DatePicker
          v-else-if="field.fieldType === 'DATE'"
          v-model="getFieldValue(field.fieldKey).dateValue"
          date-format="yy-mm-dd"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors[field.fieldKey] }"
          show-icon
        />

        <!-- 選択 -->
        <Select
          v-else-if="field.fieldType === 'SELECT'"
          v-model="getFieldValue(field.fieldKey).textValue"
          :options="field.optionsJson ? JSON.parse(field.optionsJson) : []"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors[field.fieldKey] }"
          :placeholder="field.placeholder || '選択してください'"
        />

        <!-- チェックボックス -->
        <div v-else-if="field.fieldType === 'CHECKBOX'" class="flex items-center gap-2">
          <Checkbox
            v-model="getFieldValue(field.fieldKey).textValue"
            true-value="true"
            false-value="false"
            :binary="false"
          />
        </div>

        <!-- デフォルト (テキスト) -->
        <InputText
          v-else
          v-model="getFieldValue(field.fieldKey).textValue"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors[field.fieldKey] }"
          :placeholder="field.placeholder || ''"
        />

        <small v-if="fieldErrors[field.fieldKey]" class="text-red-500">{{ fieldErrors[field.fieldKey] }}</small>
      </div>

      <div class="flex items-center gap-2">
        <Checkbox v-model="submitImmediately" :binary="true" input-id="submitNow" />
        <label for="submitNow" class="text-sm">すぐに提出する</label>
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button
        :label="isEdit ? '更新' : '提出'"
        icon="pi pi-check"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
