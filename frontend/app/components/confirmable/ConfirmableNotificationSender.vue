<script setup lang="ts">
import type { ConfirmableNotificationPriority, ConfirmableNotificationTemplate, ConfirmableRecipientGroup, ConfirmableTarget, ConfirmableRecipientPreviewRequest, CreateConfirmableNotificationRequest, UnconfirmedVisibility } from '~/types/confirmable'

const props = defineProps<{ scopeType: 'TEAM' | 'ORGANIZATION'; scopeId: string; groupsVersion?: number }>()
const emit = defineEmits<{ sent: [] }>()
const { sendNotification, previewRecipients, listTemplates, listRecipientGroups } = useConfirmableNotificationApi()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const toast = useToast()
const sending = ref(false)
const showHelp = ref(false)
const templates = ref<ConfirmableNotificationTemplate[]>([])
const groups = ref<ConfirmableRecipientGroup[]>([])
const selectedTemplateId = ref<number | null>(null)
const selectedGroupId = ref<string | null>(null)
const audienceMode = ref<'DEFAULT' | 'TARGETS' | 'GROUP'>('DEFAULT')
const selectedTargets = ref<ConfirmableTarget[]>([])
const title = ref('')
const body = ref('')
const priority = ref<ConfirmableNotificationPriority>('NORMAL')
const deadlineAt = ref<Date | null>(null)
const unconfirmedVisibility = ref<UnconfirmedVisibility>('CREATOR_AND_ADMIN')
const acceptedCount = ref<number | null>(null)
const estimatedCount = ref<number | null>(null)
const previewing = ref(false)
let previewSequence = 0
const templateOptions = computed(() => [{ label: t('label.optional'), value: null }, ...templates.value.map(template => ({ label: template.name, value: template.id }))])
const groupOptions = computed(() => [{ label: t('confirmable.current_scope'), value: null }, ...groups.value.map(group => ({ label: group.name, value: group.id }))])
const audienceModeOptions = computed(() => ['DEFAULT', 'TARGETS', 'GROUP'].map(value => ({ label: t(`confirmable.audience_mode.${value}`), value })))
const priorityOptions = computed(() => ['NORMAL', 'HIGH', 'URGENT'].map(value => ({ label: t(`confirmable.priority.${value}`), value })))
const visibilityOptions = computed(() => ['HIDDEN', 'CREATOR_AND_ADMIN', 'ALL_MEMBERS'].map(value => ({ label: t(`confirmable.unconfirmed_visibility.${value}`), value })))

function applyTemplate(templateId: number | null) {
  const template = templates.value.find(item => item.id === templateId)
  if (!template) return
  title.value = template.title
  body.value = template.body ?? ''
  priority.value = template.defaultPriority
  selectedGroupId.value = groups.value.some(group => group.id === template.defaultRecipientGroupId) ? template.defaultRecipientGroupId : null
  audienceMode.value = selectedGroupId.value ? 'GROUP' : 'DEFAULT'
}
async function loadOptions() {
  try {
    const [templateResponse, groupResponse] = await Promise.all([listTemplates(props.scopeType, props.scopeId), listRecipientGroups(props.scopeType, props.scopeId)])
    templates.value = templateResponse.data
    groups.value = groupResponse.data
    if (selectedGroupId.value && !groups.value.some(group => group.id === selectedGroupId.value)) {
      selectedGroupId.value = null
      audienceMode.value = 'DEFAULT'
    }
  } catch (error) { handleApiError(error, 'confirmable sender options') }
}
function audienceRequest(): ConfirmableRecipientPreviewRequest | null {
  if (audienceMode.value === 'GROUP') return selectedGroupId.value ? { recipientGroupId: selectedGroupId.value } : null
  if (audienceMode.value === 'TARGETS') return selectedTargets.value.length ? { targets: selectedTargets.value } : null
  return {}
}
async function loadPreview() {
  const current = ++previewSequence
  const audience = audienceRequest()
  estimatedCount.value = null
  if (!audience || !props.scopeId) return
  previewing.value = true
  try {
    const response = await previewRecipients(props.scopeType, props.scopeId, audience)
    if (current === previewSequence) estimatedCount.value = response.data.estimatedRecipientCount
  } catch (error) {
    if (current === previewSequence) handleApiError(error, 'confirmable recipient preview')
  } finally { if (current === previewSequence) previewing.value = false }
}
async function onSend() {
  if (!title.value.trim()) { toast.add({ severity: 'warn', summary: t('confirmable.title_required'), life: 3000 }); return }
  const audience = audienceRequest()
  if (!audience) { toast.add({ severity: 'warn', summary: t('confirmable.targets_required'), life: 3000 }); return }
  if (estimatedCount.value === null || estimatedCount.value === 0) { toast.add({ severity: 'warn', summary: t('confirmable.preview_required'), life: 3000 }); return }
  sending.value = true
  try {
    const request: CreateConfirmableNotificationRequest = { title: title.value.trim(), priority: priority.value, unconfirmedVisibility: unconfirmedVisibility.value, ...audience }
    if (body.value.trim()) request.body = body.value.trim()
    if (deadlineAt.value) request.deadlineAt = deadlineAt.value.toISOString()
    if (selectedTemplateId.value !== null) request.templateId = selectedTemplateId.value
    const response = await sendNotification(props.scopeType, props.scopeId, request)
    acceptedCount.value = response.data.estimatedRecipientCount
    localStorage.setItem('confirmable.lastUnconfirmedVisibility', unconfirmedVisibility.value)
    title.value = ''; body.value = ''; selectedTemplateId.value = null; selectedGroupId.value = null; selectedTargets.value = []; audienceMode.value = 'DEFAULT'
    toast.add({ severity: 'success', summary: t('confirmable.accepted'), detail: t('confirmable.accepted_count', { count: response.data.estimatedRecipientCount }), life: 4000 })
    emit('sent')
  } catch (error) { handleApiError(error, 'confirmable notification send') } finally { sending.value = false }
}
watch([audienceMode, selectedGroupId, selectedTargets, () => props.scopeId], loadPreview, { deep: true })
watch(() => props.groupsVersion, loadOptions)
onMounted(async () => {
  const stored = localStorage.getItem('confirmable.lastUnconfirmedVisibility')
  if (stored === 'HIDDEN' || stored === 'CREATOR_AND_ADMIN' || stored === 'ALL_MEMBERS') unconfirmedVisibility.value = stored
  await loadOptions()
  await loadPreview()
})
</script>

<template>
  <SectionCard :title="$t('confirmable.send')">
    <template #header-actions><Button icon="pi pi-question-circle" text rounded :aria-label="$t('button.help')" @click="showHelp = true" /></template>
    <div class="flex flex-col gap-4">
      <Message v-if="acceptedCount !== null" severity="success" :closable="false">{{ $t('confirmable.accepted_count', { count: acceptedCount }) }}</Message>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.template') }}</label><Select v-model="selectedTemplateId" :options="templateOptions" option-label="label" option-value="value" @change="applyTemplate(selectedTemplateId)" /></div>
      <div class="flex flex-col gap-1">
        <label>{{ $t('confirmable.audience') }}</label>
        <Select v-model="audienceMode" :options="audienceModeOptions" option-label="label" option-value="value" />
        <small v-if="audienceMode === 'DEFAULT'">{{ $t('confirmable.current_scope_help') }}</small>
        <ConfirmableTargetPicker v-if="audienceMode === 'TARGETS'" v-model="selectedTargets" :scope-type="scopeType" :scope-id="scopeId" />
        <Select v-if="audienceMode === 'GROUP'" v-model="selectedGroupId" :options="groupOptions.filter(option => option.value !== null)" option-label="label" option-value="value" :placeholder="$t('confirmable.select_group')" />
      </div>
      <Message v-if="previewing" severity="info" :closable="false">{{ $t('confirmable.preview_loading') }}</Message>
      <Message v-else-if="estimatedCount !== null" :severity="estimatedCount ? 'info' : 'warn'" :closable="false">{{ $t('confirmable.preview_count', { count: estimatedCount }) }}</Message>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.message_title') }}</label><InputText v-model="title" /></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.message_body') }}</label><Textarea v-model="body" rows="4" /></div>
      <div class="grid gap-4 md:grid-cols-2"><div class="flex flex-col gap-1"><label>{{ $t('confirmable.priority_label') }}</label><Select v-model="priority" :options="priorityOptions" option-label="label" option-value="value" /></div><div class="flex flex-col gap-1"><label>{{ $t('confirmable.deadline') }}</label><DatePicker v-model="deadlineAt" show-time hour-format="24" /></div></div>
      <div class="flex flex-col gap-1"><label>{{ $t('confirmable.unconfirmed_visibility.label') }}</label><Select v-model="unconfirmedVisibility" data-testid="sender-visibility-select" :options="visibilityOptions" option-label="label" option-value="value" /></div>
      <div><Button :label="$t('confirmable.send')" icon="pi pi-send" :loading="sending" :disabled="previewing || estimatedCount === null || estimatedCount === 0" @click="onSend" /></div>
    </div>
  </SectionCard>
  <Dialog v-model:visible="showHelp" modal :header="$t('confirmable.help.title')" :style="{ width: 'min(34rem, 95vw)' }"><p>{{ $t('confirmable.help.body') }}</p></Dialog>
</template>
