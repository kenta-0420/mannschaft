<script setup lang="ts">
import { z } from 'zod'
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import type { ActivityDetailResponse, ActivityTemplate } from '~/types/activity'
import {
  activityFieldValues,
  activityRawFieldValues,
  mergeActivityEditedFieldValues,
  activityParticipantUpdate,
} from '~/utils/activityDetail'
import { toYmd, type ActivityFieldValue } from '~/utils/activityFields'

const props = defineProps<{ record: ActivityDetailResponse }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n()
const { updateActivity, getTemplates } = useActivityApi()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()
const { handleApiError } = useErrorHandler()
const notification = useNotification()
const { defineField, handleSubmit, errors, resetForm } = useForm({
  validationSchema: toTypedSchema(
    z.object({
      title: z.string().trim().min(1).max(200),
      description: z.string().max(10000),
    }),
  ),
})
const [title] = defineField('title')
const [description] = defineField('description')
const activityDate = ref<Date | null>(null)
const endDate = ref<Date | null>(null)
const startTime = ref<Date | null>(null)
const endTime = ref<Date | null>(null)
const templateId = ref<number | null>(null)
const templates = ref<ActivityTemplate[]>([])
const visibility = ref<'PUBLIC' | 'MEMBERS_ONLY'>('MEMBERS_ONLY')
const inputs = ref<Record<string, ActivityFieldValue>>({})
const initialInputs = ref<Record<string, ActivityFieldValue>>({})
const participantUserIds = ref<number[]>([])
const initialParticipantUserIds = ref<number[]>([])
const members = ref<Array<{ userId: number; displayName: string }>>([])
const loadingMembers = ref(false)
const membersError = shallowRef<unknown>(null)
let membersGeneration = 0
const participantOptions = computed(() => [
  ...new Map(
    [...props.record.participants, ...members.value].map((member) => [member.userId, member]),
  ).values(),
])
async function loadMembers(): Promise<void> {
  const generation = ++membersGeneration
  const record = props.record
  loadingMembers.value = true
  membersError.value = null
  try {
    if (!record.scopePublicId) throw new Error('活動記録の所属スコープを解決できません')
    const result =
      record.scopeType === 'TEAM'
        ? await teamApi.getAllMembers(record.scopePublicId)
        : await orgApi.getAllMembers(record.scopePublicId)
    if (generation === membersGeneration) members.value = result.data
  } catch (error) {
    if (generation !== membersGeneration) return
    membersError.value = error
    handleApiError(error, '活動記録の参加者候補取得')
  } finally {
    if (generation === membersGeneration) loadingMembers.value = false
  }
}
onBeforeUnmount(() => {
  membersGeneration++
})
const saving = ref(false)
const fields = computed(
  () =>
    templates.value.find((item) => item.id === templateId.value)?.fields ??
    props.record.templateFields,
)
const visibilityOptions = computed(() => [
  { label: t('activity.create.visibilityMembersOnly'), value: 'MEMBERS_ONLY' },
  { label: t('activity.create.visibilityPublic'), value: 'PUBLIC' },
])
function timeDate(value: string | null): Date | null {
  return value ? new Date(`2000-01-01T${value}`) : null
}
function timeString(value: Date | null): string | null {
  return value
    ? `${String(value.getHours()).padStart(2, '0')}:${String(value.getMinutes()).padStart(2, '0')}:${String(value.getSeconds()).padStart(2, '0')}`
    : null
}
watch(visible, async (open) => {
  if (!open) {
    membersGeneration++
    return
  }
  const record = props.record
  participantUserIds.value = record.participants.map((participant) => participant.userId)
  initialParticipantUserIds.value = [...participantUserIds.value]
  members.value = []
  void loadMembers()
  resetForm({ values: { title: record.title, description: record.description ?? '' } })
  activityDate.value = new Date(`${record.activityDate}T00:00:00`)
  endDate.value = record.activityEndDate ? new Date(`${record.activityEndDate}T00:00:00`) : null
  startTime.value = timeDate(record.activityTimeStart)
  endTime.value = timeDate(record.activityTimeEnd)
  templateId.value = record.templateId
  visibility.value = record.visibility
  inputs.value = activityFieldValues(record)
  for (const field of record.templateFields) {
    const value = inputs.value[field.fieldKey]
    if (
      (field.fieldType === 'DATE' || field.fieldType === 'DATETIME') &&
      typeof value === 'string'
    ) {
      const date = new Date(field.fieldType === 'DATE' ? `${value}T00:00:00` : value)
      if (Number.isFinite(date.getTime())) inputs.value[field.fieldKey] = date
    }
  }
  initialInputs.value = Object.fromEntries(
    Object.entries(inputs.value).map(([key, value]) => [
      key,
      value instanceof Date ? new Date(value.getTime()) : value,
    ]),
  )
  try {
    templates.value = (await getTemplates(record.scopeType, String(record.scopeId))).data
  } catch (error) {
    handleApiError(error, '活動テンプレート取得')
  }
})
const save = handleSubmit(async (values) => {
  if (!props.record.canEdit || props.record.metadataOnly || !activityDate.value || saving.value)
    return
  saving.value = true
  try {
    if (props.record.version === null) throw new Error('活動記録の更新版を取得できません')
    await updateActivity(props.record.id, {
      ...values,
      activityDate: toYmd(activityDate.value),
      activityEndDate: endDate.value ? toYmd(endDate.value) : null,
      activityTimeStart: timeString(startTime.value),
      activityTimeEnd: timeString(endTime.value),
      templateId: templateId.value,
      visibility: visibility.value,
      fieldValues: mergeActivityEditedFieldValues(
        activityRawFieldValues(props.record),
        fields.value,
        initialInputs.value,
        inputs.value,
      ),
      version: props.record.version,
      ...(activityParticipantUpdate(initialParticipantUserIds.value, participantUserIds.value) !==
      undefined
        ? { participantUserIds: [...participantUserIds.value] }
        : {}),
    })
    notification.success(t('activity.detail.updated'))
    visible.value = false
    emit('saved')
  } catch (error) {
    handleApiError(error, '活動記録下書き保存')
  } finally {
    saving.value = false
  }
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('activity.detail.edit')"
    :style="{ width: '640px', maxWidth: '95vw' }"
  >
    <form id="activity-edit-form" class="space-y-4" @submit="save">
      <div>
        <label for="activity-edit-title">{{ t('activity.create.titleLabel') }}</label
        ><InputText
          id="activity-edit-title"
          v-model="title"
          class="min-h-11 w-full text-base"
          data-testid="activity-edit-title"
        />
        <p v-if="errors.title" class="text-red-600">{{ errors.title }}</p>
      </div>
      <div>
        <label for="activity-edit-date">{{ t('activity.create.dateLabel') }}</label
        ><DatePicker
          v-model="activityDate"
          input-class="min-h-11 text-base"
          input-id="activity-edit-date"
          date-format="yy/mm/dd"
          show-icon
          class="w-full"
        />
      </div>
      <div>
        <label for="activity-edit-end-date">{{ t('activity.detail.endDate') }}</label
        ><DatePicker
          v-model="endDate"
          input-class="min-h-11 text-base"
          input-id="activity-edit-end-date"
          date-format="yy/mm/dd"
          show-icon
          class="w-full"
          show-clear
        />
      </div>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div>
          <label for="activity-edit-start">{{ t('activity.detail.startTime') }}</label
          ><DatePicker
            v-model="startTime"
            input-class="min-h-11 text-base"
            input-id="activity-edit-start"
            time-only
            hour-format="24"
            class="w-full"
          />
        </div>
        <div>
          <label for="activity-edit-end">{{ t('activity.detail.endTime') }}</label
          ><DatePicker
            v-model="endTime"
            input-class="min-h-11 text-base"
            input-id="activity-edit-end"
            time-only
            hour-format="24"
            class="w-full"
          />
        </div>
      </div>
      <div>
        <label for="activity-edit-template">{{ t('activity.create.templateLabel') }}</label
        ><Select
          v-model="templateId"
          input-id="activity-edit-template"
          :options="templates"
          option-label="name"
          option-value="id"
          :disabled="record.templateId !== null"
          class="min-h-11 w-full"
        />
      </div>
      <div>
        <label for="activity-edit-visibility">{{ t('activity.create.visibilityLabel') }}</label
        ><Select
          v-model="visibility"
          input-id="activity-edit-visibility"
          :options="visibilityOptions"
          option-label="label"
          option-value="value"
          class="min-h-11 w-full"
        />
      </div>
      <div>
        <label for="activity-edit-description">{{ t('activity.create.descriptionLabel') }}</label
        ><Textarea
          id="activity-edit-description"
          v-model="description"
          rows="4"
          class="min-h-11 w-full text-base"
          data-testid="activity-edit-description"
        />
        <p v-if="errors.description" class="text-red-600">{{ errors.description }}</p>
      </div>
      <ActivityFieldInputs v-model="inputs" :fields="fields" />
      <div>
        <label for="activity-edit-participants">{{ t('activity.detail.participants') }}</label>
        <p class="mb-2 text-sm text-surface-500">{{ t('activity.detail.participantsHint') }}</p>
        <DashboardErrorState v-if="membersError" :error="membersError" @retry="loadMembers" />
        <MultiSelect
          v-else
          v-model="participantUserIds"
          input-id="activity-edit-participants"
          :options="participantOptions"
          option-label="displayName"
          option-value="userId"
          :loading="loadingMembers"
          :disabled="loadingMembers"
          filter
          display="chip"
          class="min-h-11 w-full"
          data-testid="activity-edit-participants"
        />
        <p
          v-if="templates.find((item) => item.id === templateId)?.isParticipantRequired"
          class="mt-1 text-sm text-surface-500"
        >
          {{ t('activity.detail.participantsRequired') }}
        </p>
      </div>
    </form>
    <template #footer>
      <Button
        class="min-h-11 min-w-11"
        :label="t('button.cancel')"
        severity="secondary"
        :disabled="saving"
        @click="visible = false"
      />
      <Button
        class="min-h-11 min-w-11"
        type="submit"
        form="activity-edit-form"
        :label="t('activity.detail.save')"
        :loading="saving"
        data-testid="activity-edit-save"
      />
    </template>
  </Dialog>
</template>
