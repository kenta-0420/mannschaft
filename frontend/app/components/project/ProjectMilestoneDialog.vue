<script setup lang="ts">
import type { CreateMilestoneRequest, MilestoneResponse } from '~/types/project'

const { t } = useI18n()

const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  form: CreateMilestoneRequest
  editing: MilestoneResponse | null
  /** 保存 API が返した項目別のエラー（400 の fieldErrors）。項目名 → メッセージ。 */
  fieldErrors?: Record<string, string>
}>()

const emit = defineEmits<{
  save: [form: CreateMilestoneRequest]
}>()

const localForm = ref<CreateMilestoneRequest>({ ...props.form })
/** 送信前に検出したエラー（タイトル未入力）。API が返したものは props.fieldErrors 側。 */
const clientErrors = ref<Record<string, string>>({})

function errorOf(field: string): string | undefined {
  return clientErrors.value[field] ?? props.fieldErrors?.[field]
}

watch(() => props.form, (val) => {
  localForm.value = { ...val }
}, { deep: true })

// 開くたびに保存値（親が props.form に入れた値）で初期化する。Esc で閉じた打ちかけの値を持ち越さない。
// 新規追加のダイアログは、閉じたときに空へ戻す。
watch(visible, (open) => {
  clientErrors.value = {}
  if (open) {
    localForm.value = { ...props.form }
  } else if (!props.editing) {
    localForm.value = { title: '', dueDate: '', sortOrder: props.form.sortOrder }
  }
})

function onSave() {
  if (!localForm.value.title?.trim()) {
    clientErrors.value = { title: t('project.milestone_dialog.title_required') }
    return
  }
  clientErrors.value = {}
  emit('save', localForm.value)
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="editing ? t('project.milestone_dialog.edit_title') : t('project.milestone_dialog.add_title')"
    modal
    class="w-full max-w-md"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('project.milestone_dialog.title_label') }}</label>
        <InputText
          v-model="localForm.title"
          class="w-full"
          :placeholder="t('project.milestone_dialog.title_placeholder')"
          :invalid="!!errorOf('title')"
          data-testid="milestone-title-input"
        />
        <small
          v-if="errorOf('title')"
          class="mt-1 block text-red-600"
          role="alert"
          data-testid="milestone-title-error"
        >{{ errorOf('title') }}</small>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('project.milestone_dialog.due_date_label') }}</label>
        <InputText v-model="localForm.dueDate" type="date" class="w-full" :invalid="!!errorOf('dueDate')" />
        <small
          v-if="errorOf('dueDate')"
          class="mt-1 block text-red-600"
          role="alert"
          data-testid="milestone-due-date-error"
        >{{ errorOf('dueDate') }}</small>
      </div>
    </div>
    <template #footer>
      <Button :label="t('project.dialog.cancel')" text @click="visible = false" />
      <Button
        :label="editing ? t('project.dialog.update') : t('project.milestone_dialog.add')"
        data-testid="milestone-save-button"
        @click="onSave"
      />
    </template>
  </Dialog>
</template>
