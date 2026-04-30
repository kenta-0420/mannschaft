<script setup lang="ts">
import type { ActivityTemplate } from '~/types/activity'

interface FieldRow {
  label: string
  fieldType: 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT' | 'CHECKBOX' | 'TEXTAREA'
  required: boolean
  isAggregatable: boolean
  optionsRaw: string
}

const props = defineProps<{
  template?: ActivityTemplate | null
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const { createTemplate, updateTemplate } = useActivityApi()
const { showError, showSuccess } = useNotification()

const name = ref('')
const description = ref('')
const fields = ref<FieldRow[]>([])
const saving = ref(false)

const { t } = useI18n()

const fieldTypeOptions = computed(() => [
  { label: t('activity.template.field_type.TEXT'), value: 'TEXT' },
  { label: t('activity.template.field_type.NUMBER'), value: 'NUMBER' },
  { label: t('activity.template.field_type.DATE'), value: 'DATE' },
  { label: t('activity.template.field_type.SELECT'), value: 'SELECT' },
  { label: t('activity.template.field_type.CHECKBOX'), value: 'CHECKBOX' },
  { label: t('activity.template.field_type.TEXTAREA'), value: 'TEXTAREA' },
])

function initForm() {
  if (props.template) {
    name.value = props.template.name
    description.value = props.template.description ?? ''
    fields.value = (props.template.fields ?? []).map((f) => ({
      label: f.fieldName,
      fieldType: f.fieldType as FieldRow['fieldType'],
      required: f.isRequired,
      isAggregatable: false,
      optionsRaw: '',
    }))
  } else {
    name.value = ''
    description.value = ''
    fields.value = []
  }
}

watch(
  () => props.visible,
  (val) => {
    if (val) initForm()
  },
  { immediate: true },
)

function addField() {
  fields.value.push({
    label: '',
    fieldType: 'TEXT',
    required: false,
    isAggregatable: false,
    optionsRaw: '',
  })
}

function removeField(index: number) {
  fields.value.splice(index, 1)
}

function onFieldTypeChange(index: number) {
  const f = fields.value[index]
  if (f.fieldType !== 'NUMBER') {
    f.isAggregatable = false
  }
  if (f.fieldType !== 'SELECT') {
    f.optionsRaw = ''
  }
}

async function handleSave() {
  if (!name.value.trim()) {
    showError(t('activity.template.name_required'))
    return
  }
  saving.value = true
  try {
    const body = {
      name: name.value.trim(),
      description: description.value.trim() || undefined,
      fields: fields.value.map((f) => ({
        label: f.label,
        fieldType: f.fieldType,
        required: f.required,
        isAggregatable: f.isAggregatable,
        options:
          f.fieldType === 'SELECT'
            ? f.optionsRaw
                .split(',')
                .map((s) => s.trim())
                .filter(Boolean)
            : [],
      })),
    }
    if (props.template) {
      await updateTemplate(props.template.id, body)
    } else {
      await createTemplate(body)
    }
    showSuccess(t('activity.template.save_success'))
    emit('saved')
    emit('update:visible', false)
  } catch {
    showError(t('activity.template.save_error'))
  } finally {
    saving.value = false
  }
}

function handleCancel() {
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    style="width: 700px"
    :header="template ? $t('activity.template.dialog_title_edit') : $t('activity.template.dialog_title_create')"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="flex flex-col gap-4">
      <!-- 基本情報 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('activity.template.name_label') }} <span class="text-red-500">*</span></label>
        <InputText v-model="name" : placeholder="$t('activity.template.name_placeholder')" class="w-full" />
      </div>
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">説明</label>
        <Textarea v-model="description" rows="2" : placeholder="$t('activity.template.description_placeholder')" class="w-full" />
      </div>

      <!-- フィールド定義テーブル -->
      <div>
        <div class="mb-2 flex items-center justify-between">
          <span class="text-sm font-medium">フィールド定義</span>
          <Button
            :label="$t('activity.template.add_field')"
            icon="pi pi-plus"
            size="small"
            outlined
            @click="addField"
          />
        </div>

        <div v-if="fields.length === 0" class="rounded-lg border border-dashed border-surface-300 py-6 text-center text-sm text-surface-400">
          フィールドがありません。「フィールドを追加」から追加してください。
        </div>

        <div v-else class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-surface-200 text-left text-xs text-surface-500">
                <th class="pb-2 pr-2">フィールド名</th>
                <th class="pb-2 pr-2">型</th>
                <th class="pb-2 pr-2 text-center">必須</th>
                <th class="pb-2 pr-2 text-center">集計対象<br />(NUMBERのみ)</th>
                <th class="pb-2 pr-2">選択肢 (SELECTのみ)</th>
                <th class="pb-2 text-center">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(field, index) in fields"
                :key="index"
                class="border-b border-surface-100"
              >
                <td class="py-2 pr-2">
                  <InputText
                    v-model="field.label"
                    : placeholder="$t('activity.template.field_name_placeholder')"
                    class="w-full"
                    size="small"
                  />
                </td>
                <td class="py-2 pr-2">
                  <Select
                    v-model="field.fieldType"
                    :options="fieldTypeOptions"
                    option-label="label"
                    option-value="value"
                    class="w-32"
                    size="small"
                    @change="onFieldTypeChange(index)"
                  />
                </td>
                <td class="py-2 pr-2 text-center">
                  <Checkbox v-model="field.required" :binary="true" />
                </td>
                <td class="py-2 pr-2 text-center">
                  <Checkbox
                    v-model="field.isAggregatable"
                    :binary="true"
                    :disabled="field.fieldType !== 'NUMBER'"
                  />
                </td>
                <td class="py-2 pr-2">
                  <InputText
                    v-if="field.fieldType === 'SELECT'"
                    v-model="field.optionsRaw"
                    : placeholder="$t('activity.template.options_placeholder')"
                    class="w-full"
                    size="small"
                  />
                  <span v-else class="text-surface-300">—</span>
                </td>
                <td class="py-2 text-center">
                  <Button
                    icon="pi pi-trash"
                    text
                    rounded
                    severity="danger"
                    size="small"
                    @click="removeField(index)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button :label="$t('button.cancel')" text @click="handleCancel" />
        <Button :label="$t('button.save')" icon="pi pi-check" :loading="saving" @click="handleSave" />
      </div>
    </template>
  </Dialog>
</template>
