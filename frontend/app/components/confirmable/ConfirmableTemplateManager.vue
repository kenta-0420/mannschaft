<script setup lang="ts">
import type { ConfirmableNotificationPriority, ConfirmableNotificationTemplate, ConfirmableRecipientGroup } from '~/types/confirmable'

const props = defineProps<{ scopeType: 'TEAM' | 'ORGANIZATION'; scopeId: string; groupsVersion?: number }>()
const emit = defineEmits<{ changed: [] }>()
const { listTemplates, createTemplate, updateTemplate, deleteTemplate, listRecipientGroups } = useConfirmableNotificationApi()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const templates = ref<ConfirmableNotificationTemplate[]>([])
const groups = ref<ConfirmableRecipientGroup[]>([])
const editingId = ref<number | null>(null)
const name = ref('')
const title = ref('')
const body = ref('')
const priority = ref<ConfirmableNotificationPriority>('NORMAL')
const defaultGroupId = ref<string | null>(null)
const loading = ref(false)
const saving = ref(false)
const priorityOptions = computed(() => ['NORMAL', 'HIGH', 'URGENT'].map(value => ({ label: t(`confirmable.priority.${value}`), value })))
const groupOptions = computed(() => [{ label: t('confirmable.current_scope'), value: null }, ...groups.value.map(group => ({ label: group.name, value: group.id }))])

async function load() {
  if (!props.scopeId) return
  loading.value = true
  try {
    const [templateResponse, groupResponse] = await Promise.all([listTemplates(props.scopeType, props.scopeId), listRecipientGroups(props.scopeType, props.scopeId)])
    templates.value = templateResponse.data
    groups.value = groupResponse.data
    if (defaultGroupId.value && !groups.value.some(group => group.id === defaultGroupId.value)) defaultGroupId.value = null
  } catch (error) { handleApiError(error, 'confirmable templates') }
  finally { loading.value = false }
}
function edit(template: ConfirmableNotificationTemplate) {
  editingId.value = template.id
  name.value = template.name
  title.value = template.title
  body.value = template.body ?? ''
  priority.value = template.defaultPriority
  defaultGroupId.value = groups.value.some(group => group.id === template.defaultRecipientGroupId) ? template.defaultRecipientGroupId : null
}
function reset() { editingId.value = null; name.value = ''; title.value = ''; body.value = ''; priority.value = 'NORMAL'; defaultGroupId.value = null }
async function save() {
  if (!name.value.trim() || !title.value.trim()) return
  saving.value = true
  try {
    const request = { name: name.value.trim(), title: title.value.trim(), body: body.value.trim(), defaultPriority: priority.value, defaultRecipientGroupId: defaultGroupId.value }
    if (editingId.value !== null) await updateTemplate(props.scopeType, props.scopeId, editingId.value, request)
    else await createTemplate(props.scopeType, props.scopeId, request)
    reset(); await load(); emit('changed')
  } catch (error) { handleApiError(error, 'confirmable template save') }
  finally { saving.value = false }
}
async function remove(templateId: number) {
  try { await deleteTemplate(props.scopeType, props.scopeId, templateId); if (editingId.value === templateId) reset(); await load(); emit('changed') }
  catch (error) { handleApiError(error, 'confirmable template delete') }
}
watch([() => props.scopeId, () => props.groupsVersion], load, { immediate: true })
</script>
<template>
  <SectionCard :title="$t('confirmable.templates')">
    <div class="flex flex-col gap-3">
      <InputText v-model="name" :placeholder="$t('confirmable.template_name')" />
      <InputText v-model="title" :placeholder="$t('confirmable.message_title')" />
      <Textarea v-model="body" :placeholder="$t('confirmable.message_body')" rows="3" />
      <Select v-model="priority" :options="priorityOptions" option-label="label" option-value="value" />
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.default_recipient_group') }}</label><Select v-model="defaultGroupId" :options="groupOptions" option-label="label" option-value="value" /><small>{{ $t('confirmable.deleted_group_fallback') }}</small></div>
      <div class="flex gap-2"><Button :label="editingId !== null ? $t('button.save') : $t('button.create')" :loading="saving" :disabled="!name.trim() || !title.trim()" @click="save" /><Button v-if="editingId !== null" :label="$t('button.cancel')" text @click="reset" /></div>
      <PageLoading v-if="loading" size="28px" />
      <DataTable v-else :value="templates"><Column field="name" :header="$t('confirmable.template_name')" /><Column field="title" :header="$t('confirmable.message_title')" /><Column :header="$t('confirmable.default_recipient_group')"><template #body="{ data }: { data: ConfirmableNotificationTemplate }">{{ groups.find(group => group.id === data.defaultRecipientGroupId)?.name ?? $t('confirmable.current_scope') }}</template></Column><Column><template #body="{ data }: { data: ConfirmableNotificationTemplate }"><Button :label="$t('button.edit')" text @click="edit(data)" /><Button :label="$t('button.delete')" text severity="danger" @click="remove(data.id)" /></template></Column></DataTable>
    </div>
  </SectionCard>
</template>
