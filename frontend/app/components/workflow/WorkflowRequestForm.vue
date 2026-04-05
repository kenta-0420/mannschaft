<script setup lang="ts">
import type { WorkflowTemplateResponse } from '~/types/workflow'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const workflowApi = useWorkflowApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()

const templates = ref<WorkflowTemplateResponse[]>([])
const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const loadingTemplates = ref(false)

const form = ref({
  templateId: null as number | null,
  title: '',
  fieldValues: '',
})

watch(
  () => props.visible,
  async (visible) => {
    if (visible) {
      resetForm()
      await loadTemplates()
    }
  },
)

async function loadTemplates() {
  loadingTemplates.value = true
  try {
    const res = await workflowApi.listTemplates(props.scopeType, props.scopeId, { size: 100 })
    templates.value = res.data.filter((t) => t.isActive)
  } catch {
    templates.value = []
  } finally {
    loadingTemplates.value = false
  }
}

async function submit() {
  if (!form.value.templateId) {
    fieldErrors.value = { templateId: 'テンプレートを選択してください' }
    return
  }

  submitting.value = true
  fieldErrors.value = {}

  try {
    await workflowApi.createRequest(props.scopeType, props.scopeId, {
      templateId: form.value.templateId,
      title: form.value.title.trim() || undefined,
      fieldValues: form.value.fieldValues.trim() || undefined,
    })
    notification.success('申請を作成しました')
    emit('saved')
    close()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0) {
      handleApiError(error, 'ワークフロー申請作成')
    }
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = { templateId: null, title: '', fieldValues: '' }
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
    header="新規申請"
    :style="{ width: '500px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >テンプレート <span class="text-red-500">*</span></label
        >
        <Select
          v-model="form.templateId"
          :options="templates"
          option-label="name"
          option-value="id"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors.templateId }"
          placeholder="テンプレートを選択"
          :loading="loadingTemplates"
        />
        <small v-if="fieldErrors.templateId" class="text-red-500">{{
          fieldErrors.templateId
        }}</small>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">タイトル</label>
        <InputText v-model="form.title" class="w-full" placeholder="申請タイトル（任意）" />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">入力値（JSON）</label>
        <Textarea
          v-model="form.fieldValues"
          rows="5"
          class="w-full"
          placeholder="フィールド値（JSON形式、任意）"
        />
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button label="作成" icon="pi pi-check" :loading="submitting" @click="submit" />
    </template>
  </Dialog>
</template>
