<script setup lang="ts">
import type { ConfirmableNotificationPriority, ConfirmableNotificationTemplate, ConfirmableRecipientGroup, CreateConfirmableNotificationRequest, UnconfirmedVisibility } from '~/types/confirmable'

const props = defineProps<{ scopeType: 'TEAM' | 'ORGANIZATION'; scopeId: string }>()
const emit = defineEmits<{ sent: [] }>()
const { sendNotification, listTemplates, listRecipientGroups } = useConfirmableNotificationApi()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const toast = useToast()
const sending = ref(false)
const showHelp = ref(false)
const templates = ref<ConfirmableNotificationTemplate[]>([])
const groups = ref<ConfirmableRecipientGroup[]>([])
const selectedTemplateId = ref<number | null>(null)
const selectedGroupId = ref<string | null>(null)
const title = ref('')
const body = ref('')
const priority = ref<ConfirmableNotificationPriority>('NORMAL')
const deadlineAt = ref<Date | null>(null)
const unconfirmedVisibility = ref<UnconfirmedVisibility>('CREATOR_AND_ADMIN')
const acceptedCount = ref<number | null>(null)
const templateOptions = computed(() => [{ label: t('label.optional'), value: null }, ...templates.value.map(template => ({ label: template.name, value: template.id }))])
const groupOptions = computed(() => [{ label: t('confirmable.current_scope'), value: null }, ...groups.value.map(group => ({ label: group.name, value: group.id }))])
const priorityOptions = computed(() => ['NORMAL', 'HIGH', 'URGENT'].map(value => ({ label: t(`confirmable.priority.${value}`), value })))
const visibilityOptions = computed(() => ['HIDDEN', 'CREATOR_AND_ADMIN', 'ALL_MEMBERS'].map(value => ({ label: t(`confirmable.unconfirmed_visibility.${value}`), value })))

function applyTemplate(templateId: number | null) {
  const template = templates.value.find(item => item.id === templateId)
  if (!template) return
  title.value = template.title
  body.value = template.body ?? ''
  priority.value = template.defaultPriority
  selectedGroupId.value = template.defaultRecipientGroupId
}
async function loadOptions() {
  try {
    const [templateResponse, groupResponse] = await Promise.all([listTemplates(props.scopeType, props.scopeId), listRecipientGroups(props.scopeType, props.scopeId)])
    templates.value = templateResponse.data
    groups.value = groupResponse.data
  } catch (error) { handleApiError(error, 'confirmable sender options') }
}
async function onSend() {
  if (!title.value.trim()) { toast.add({ severity: 'warn', summary: t('confirmable.title_required'), life: 3000 }); return }
  sending.value = true
  try {
    const request: CreateConfirmableNotificationRequest = { title: title.value.trim(), priority: priority.value, unconfirmedVisibility: unconfirmedVisibility.value, ...(selectedGroupId.value ? { recipientGroupId: selectedGroupId.value } : { targets: [{ type: props.scopeType, id: Number(props.scopeId) }] }) }
    if (body.value.trim()) request.body = body.value.trim()
    if (deadlineAt.value) request.deadlineAt = deadlineAt.value.toISOString()
    if (selectedTemplateId.value !== null) request.templateId = selectedTemplateId.value
    const response = await sendNotification(props.scopeType, props.scopeId, request)
    acceptedCount.value = response.data.estimatedRecipientCount
    title.value = ''; body.value = ''; selectedTemplateId.value = null; selectedGroupId.value = null
    toast.add({ severity: 'success', summary: t('confirmable.accepted'), detail: t('confirmable.accepted_count', { count: response.data.estimatedRecipientCount }), life: 4000 })
    emit('sent')
  } catch (error) { handleApiError(error, 'confirmable notification send') } finally { sending.value = false }
}
onMounted(loadOptions)
</script>

<template>
  <SectionCard :title="$t('confirmable.send')">
    <template #header-actions><Button icon="pi pi-question-circle" text rounded :aria-label="$t('button.help')" @click="showHelp = true" /></template>
    <div class="flex flex-col gap-4">
      <Message v-if="acceptedCount !== null" severity="success" :closable="false">{{ $t('confirmable.accepted_count', { count: acceptedCount }) }}</Message>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.template') }}</label><Select v-model="selectedTemplateId" :options="templateOptions" option-label="label" option-value="value" @change="applyTemplate(selectedTemplateId)" /></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.audience') }}</label><Select v-model="selectedGroupId" :options="groupOptions" option-label="label" option-value="value" /><small>{{ selectedGroupId ? $t('confirmable.group_audience_help') : $t('confirmable.current_scope_help') }}</small></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.message_title') }}</label><InputText v-model="title" /></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.message_body') }}</label><Textarea v-model="body" rows="4" /></div>
      <div class="grid gap-4 md:grid-cols-2"><div class="flex flex-col gap-1"><label>{{ $t('confirmable.priority_label') }}</label><Select v-model="priority" :options="priorityOptions" option-label="label" option-value="value" /></div><div class="flex flex-col gap-1"><label>{{ $t('confirmable.deadline') }}</label><DatePicker v-model="deadlineAt" show-time hour-format="24" /></div></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.unconfirmed_visibility.label') }}</label><Select v-model="unconfirmedVisibility" :options="visibilityOptions" option-label="label" option-value="value" /></div>
      <div><Button :label="$t('confirmable.send')" icon="pi pi-send" :loading="sending" @click="onSend" /></div>
    </div>
  </SectionCard>
  <Dialog v-model:visible="showHelp" modal :header="$t('confirmable.help.title')" :style="{ width: 'min(34rem, 95vw)' }"><p>{{ $t('confirmable.help.body') }}</p></Dialog>
</template>
