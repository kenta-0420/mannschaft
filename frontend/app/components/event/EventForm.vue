<script setup lang="ts">
import type { AttendanceMode, EventVisibility } from '~/types/event'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  eventId?: number
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const eventApi = useEventApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()
const { t } = useI18n()

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.eventId)

const visibilityOptions = computed(() => [
  { label: t('event.visibility.MEMBERS_ONLY'), value: 'MEMBERS_ONLY' as EventVisibility },
  { label: t('event.visibility.SUPPORTERS_AND_ABOVE'), value: 'SUPPORTERS_AND_ABOVE' as EventVisibility },
  { label: t('event.visibility.PUBLIC'), value: 'PUBLIC' as EventVisibility },
])

const form = ref({
  subtitle: '',
  slug: '',
  summary: '',
  venueName: '',
  venueAddress: '',
  visibility: 'MEMBERS_ONLY' as EventVisibility,
  maxCapacity: null as number | null,
  isApprovalRequired: false,
  registrationStartsAt: null as Date | null,
  registrationEndsAt: null as Date | null,
  attendanceMode: 'NONE' as AttendanceMode,
  preSurveyId: null as number | null,
  postSurveyId: null as number | null,
  allowProxyAttendance: false,
  isProxyAutoAccept: false,
})

watch(
  () => [props.visible, props.eventId],
  async ([visible, eventId]) => {
    if (visible && eventId) {
      try {
        const res = await eventApi.getEvent(props.scopeType, props.scopeId, eventId as number)
        const d = res.data
        form.value.subtitle = d.content?.subtitle ?? ''
        form.value.slug = d.content?.slug ?? ''
        form.value.summary = d.content?.summary ?? ''
        form.value.venueName = d.venue?.venueName ?? ''
        form.value.venueAddress = d.venue?.venueAddress ?? ''
        form.value.visibility = d.meta?.visibility ?? 'MEMBERS_ONLY'
        form.value.maxCapacity = d.registration?.maxCapacity ?? null
        form.value.isApprovalRequired = d.registration?.isApprovalRequired ?? false
        form.value.registrationStartsAt = d.registration?.registrationStartsAt
          ? new Date(d.registration.registrationStartsAt)
          : null
        form.value.registrationEndsAt = d.registration?.registrationEndsAt
          ? new Date(d.registration.registrationEndsAt)
          : null
        form.value.attendanceMode = (d.registration?.attendanceMode ?? 'NONE') as AttendanceMode
        form.value.preSurveyId = d.registration?.preSurveyId ?? null
        form.value.postSurveyId = d.registration?.postSurveyId ?? null
        form.value.allowProxyAttendance = (d.registration as Record<string, unknown>)?.allowProxyAttendance as boolean ?? false
        form.value.isProxyAutoAccept = (d.registration as Record<string, unknown>)?.isProxyAutoAccept as boolean ?? false
      } catch {
        notification.error('イベント情報の取得に失敗しました')
      }
    } else if (visible && !eventId) {
      resetForm()
    }
  },
)

async function submit() {
  if (!form.value.subtitle.trim()) {
    fieldErrors.value = { subtitle: 'イベント名は必須です' }
    return
  }

  submitting.value = true
  fieldErrors.value = {}

  const body: Record<string, unknown> = {
    subtitle: form.value.subtitle.trim(),
    slug: form.value.slug.trim() || undefined,
    summary: form.value.summary.trim() || undefined,
    venueName: form.value.venueName.trim() || undefined,
    venueAddress: form.value.venueAddress.trim() || undefined,
    visibility: form.value.visibility,
    maxCapacity: form.value.maxCapacity || undefined,
    isApprovalRequired: form.value.isApprovalRequired,
    registrationStartsAt: form.value.registrationStartsAt
      ? form.value.registrationStartsAt.toISOString()
      : undefined,
    registrationEndsAt: form.value.registrationEndsAt
      ? form.value.registrationEndsAt.toISOString()
      : undefined,
    attendanceMode: form.value.attendanceMode,
    preSurveyId: form.value.preSurveyId ?? undefined,
    postSurveyId: form.value.postSurveyId ?? undefined,
    allow_proxy_attendance: form.value.allowProxyAttendance,
    is_proxy_auto_accept: form.value.allowProxyAttendance ? form.value.isProxyAutoAccept : false,
  }

  try {
    if (isEdit.value && props.eventId) {
      await eventApi.updateEvent(props.scopeType, props.scopeId, props.eventId, body)
      notification.success('イベントを更新しました')
    } else {
      await eventApi.createEvent(props.scopeType, props.scopeId, body)
      notification.success('イベントを作成しました')
    }
    emit('saved')
    close()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0) {
      handleApiError(error, isEdit.value ? 'イベント更新' : 'イベント作成')
    }
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    subtitle: '',
    slug: '',
    summary: '',
    venueName: '',
    venueAddress: '',
    visibility: 'MEMBERS_ONLY',
    maxCapacity: null,
    isApprovalRequired: false,
    registrationStartsAt: null,
    registrationEndsAt: null,
    attendanceMode: 'NONE',
    preSurveyId: null,
    postSurveyId: null,
    allowProxyAttendance: false,
    isProxyAutoAccept: false,
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
    :header="isEdit ? 'イベントを編集' : 'イベントを作成'"
    :style="{ width: '600px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >イベント名 <span class="text-red-500">*</span></label
        >
        <InputText
          v-model="form.subtitle"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors.subtitle }"
          placeholder="イベントのタイトル"
          data-testid="team-event-title-input"
        />
        <small v-if="fieldErrors.subtitle" class="text-red-500">{{ fieldErrors.subtitle }}</small>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">スラッグ</label>
        <InputText v-model="form.slug" class="w-full" placeholder="URL用の識別子（任意）" />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">概要</label>
        <Textarea v-model="form.summary" rows="3" class="w-full" placeholder="イベントの概要説明" />
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">会場名</label>
          <InputText v-model="form.venueName" class="w-full" placeholder="開催場所" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">会場住所</label>
          <InputText v-model="form.venueAddress" class="w-full" placeholder="住所" />
        </div>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label for="event-registration-starts-at" class="mb-1 block text-sm font-medium">受付開始日時</label>
          <DatePicker
            v-model="form.registrationStartsAt"
            input-id="event-registration-starts-at"
            show-time
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>
        <div>
          <label for="event-registration-ends-at" class="mb-1 block text-sm font-medium">受付終了日時</label>
          <DatePicker
            v-model="form.registrationEndsAt"
            input-id="event-registration-ends-at"
            show-time
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">定員</label>
          <InputNumber v-model="form.maxCapacity" class="w-full" placeholder="制限なし" :min="1" />
        </div>
        <div class="flex flex-col justify-end gap-2">
          <div class="flex items-center gap-2">
            <Checkbox
              v-model="form.isApprovalRequired"
              :binary="true"
              input-id="isApprovalRequired"
            />
            <label for="isApprovalRequired" class="text-sm">承認制</label>
          </div>
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('event.visibility.label') }}</label>
        <SelectButton
          v-model="form.visibility"
          :options="visibilityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <!-- 参加方式 -->
      <AttendanceModeSelector v-model="form.attendanceMode" />

      <!-- 代理出席設定 -->
      <div class="flex flex-col gap-2">
        <div class="flex items-center gap-3">
          <Checkbox
            v-model="form.allowProxyAttendance"
            :binary="true"
            input-id="allowProxy"
          />
          <label for="allowProxy" class="text-sm text-gray-700 dark:text-gray-300">
            {{ $t('proxy.delegation.allow_proxy') }}
          </label>
        </div>
        <div v-if="form.allowProxyAttendance" class="ml-6 flex flex-col gap-1">
          <div class="flex items-center gap-3">
            <Checkbox
              v-model="form.isProxyAutoAccept"
              :binary="true"
              input-id="autoAccept"
            />
            <label for="autoAccept" class="text-sm text-gray-700 dark:text-gray-300">
              {{ $t('proxy.delegation.auto_accept') }}
            </label>
          </div>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ $t('proxy.delegation.auto_accept_note') }}
          </p>
        </div>
      </div>

      <!-- アンケート -->
      <div class="grid grid-cols-2 gap-3">
        <EventSurveyPicker
          v-model="form.preSurveyId"
          :label="$t('event.survey.preSurvey')"
        />
        <EventSurveyPicker
          v-model="form.postSurveyId"
          :label="$t('event.survey.postSurvey')"
        />
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button
        :label="isEdit ? '更新' : '作成'"
        icon="pi pi-check"
        :loading="submitting"
        data-testid="team-event-form-submit"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
