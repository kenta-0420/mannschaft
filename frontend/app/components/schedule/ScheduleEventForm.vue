<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  scheduleId?: number
  initialDate?: string
  visible: boolean
  isPersonal?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const scheduleApi = useScheduleApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.scheduleId)

const form = ref({
  title: '',
  description: '',
  location: '',
  startDate: null as Date | null,
  startTime: '',
  endDate: null as Date | null,
  endTime: '',
  allDay: false,
  color: '#6366f1',
})

watch(
  () => [props.visible, props.scheduleId],
  async ([visible, scheduleId]) => {
    if (visible && scheduleId) {
      try {
        const res = props.isPersonal
          ? await scheduleApi.listPersonalSchedules()
          : await scheduleApi.getSchedule(props.scopeType, props.scopeId, scheduleId as number)
        const data = (res as { data: Record<string, unknown> }).data as Record<string, unknown>
        form.value.title = (data.title as string) ?? ''
        form.value.description = (data.description as string) ?? ''
        form.value.location = (data.location as string) ?? ''
        form.value.allDay = (data.allDay as boolean) ?? false
        if (data.startAt) {
          const start = new Date(data.startAt as string)
          form.value.startDate = start
          form.value.startTime = start.toTimeString().slice(0, 5)
        }
        if (data.endAt) {
          const end = new Date(data.endAt as string)
          form.value.endDate = end
          form.value.endTime = end.toTimeString().slice(0, 5)
        }
      } catch {
        notification.error('イベント情報の取得に失敗しました')
      }
    } else if (visible && !scheduleId) {
      resetForm()
      if (props.initialDate) {
        form.value.startDate = new Date(props.initialDate)
        form.value.endDate = new Date(props.initialDate)
      }
    }
  },
)

function buildDateTimeStr(date: Date | null, time: string): string {
  if (!date) return ''
  const dateStr = date.toISOString().split('T')[0]
  return time ? `${dateStr}T${time}:00` : `${dateStr}T00:00:00`
}

async function submit() {
  if (!form.value.title.trim()) {
    fieldErrors.value = { title: 'タイトルは必須です' }
    return
  }
  submitting.value = true
  fieldErrors.value = {}

  const body: Record<string, unknown> = {
    title: form.value.title.trim(),
    description: form.value.description.trim() || undefined,
    location: form.value.location.trim() || undefined,
    allDay: form.value.allDay,
    startAt: buildDateTimeStr(form.value.startDate, form.value.allDay ? '' : form.value.startTime),
    endAt: buildDateTimeStr(form.value.endDate, form.value.allDay ? '' : form.value.endTime),
  }
  if (props.isPersonal) {
    body.color = form.value.color
  }

  try {
    if (props.isPersonal) {
      if (isEdit.value && props.scheduleId) {
        await scheduleApi.updatePersonalSchedule(props.scheduleId, body)
      } else {
        await scheduleApi.createPersonalSchedule(body)
      }
    } else {
      if (isEdit.value && props.scheduleId) {
        await scheduleApi.updateSchedule(props.scopeType, props.scopeId, props.scheduleId, body)
      } else {
        await scheduleApi.createSchedule(props.scopeType, props.scopeId, body)
      }
    }
    notification.success(isEdit.value ? 'イベントを更新しました' : 'イベントを作成しました')
    emit('saved')
    close()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0)
      handleApiError(error, isEdit.value ? 'スケジュール更新' : 'スケジュール作成')
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    title: '',
    description: '',
    location: '',
    startDate: null,
    startTime: '09:00',
    endDate: null,
    endTime: '10:00',
    allDay: false,
    color: '#6366f1',
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
    :style="{ width: '500px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >タイトル <span class="text-red-500">*</span></label
        >
        <InputText
          v-model="form.title"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors.title }"
        />
        <small v-if="fieldErrors.title" class="text-red-500">{{ fieldErrors.title }}</small>
      </div>
      <div class="flex items-center gap-2">
        <ToggleSwitch v-model="form.allDay" />
        <label class="text-sm">終日</label>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label for="schedule-start-date" class="mb-1 block text-sm font-medium">開始日</label>
          <DatePicker v-model="form.startDate" input-id="schedule-start-date" date-format="yy/mm/dd" class="w-full" show-icon />
        </div>
        <div v-if="!form.allDay">
          <label class="mb-1 block text-sm font-medium">開始時刻</label>
          <InputText v-model="form.startTime" type="time" class="w-full" />
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label for="schedule-end-date" class="mb-1 block text-sm font-medium">終了日</label>
          <DatePicker v-model="form.endDate" input-id="schedule-end-date" date-format="yy/mm/dd" class="w-full" show-icon />
        </div>
        <div v-if="!form.allDay">
          <label class="mb-1 block text-sm font-medium">終了時刻</label>
          <InputText v-model="form.endTime" type="time" class="w-full" />
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">場所</label>
        <InputText v-model="form.location" class="w-full" placeholder="場所（任意）" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="form.description" rows="3" class="w-full" />
      </div>
      <div v-if="isPersonal">
        <label class="mb-1 block text-sm font-medium">色</label>
        <div class="flex gap-2">
          <button
            v-for="c in ['#6366f1', '#ef4444', '#22c55e', '#f59e0b', '#3b82f6', '#ec4899']"
            :key="c"
            class="h-8 w-8 rounded-full border-2"
            :class="
              form.color === c ? 'border-primary ring-2 ring-primary/30' : 'border-surface-300'
            "
            :style="{ backgroundColor: c }"
            @click="form.color = c"
          />
        </div>
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
