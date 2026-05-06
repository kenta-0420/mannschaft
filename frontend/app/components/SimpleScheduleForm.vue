<script setup lang="ts">
const props = defineProps<{
  visible: boolean
  initialDate?: string
  scopeType?: 'team' | 'organization'
  scopeId?: number
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

const form = ref({
  title: '',
  allDay: false,
  startDate: null as Date | null,
  startTime: '09:00',
  endDate: null as Date | null,
  endTime: '10:00',
})

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
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

  const body = {
    title: form.value.title.trim(),
    allDay: form.value.allDay,
    startAt: buildDateTimeStr(form.value.startDate, form.value.allDay ? '' : form.value.startTime),
    endAt: (() => {
      if (form.value.allDay && form.value.endDate) {
        const d = new Date(form.value.endDate)
        d.setDate(d.getDate() + 1)
        return buildDateTimeStr(d, '')
      }
      return buildDateTimeStr(form.value.endDate, form.value.allDay ? '' : form.value.endTime)
    })(),
  }

  try {
    if (props.isPersonal) {
      await scheduleApi.createMySchedule(body)
    } else {
      await scheduleApi.createSchedule(props.scopeType!, props.scopeId!, body)
    }
    notification.success('予定を追加しました')
    emit('saved')
    close()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0)
      handleApiError(error, '予定の追加')
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    title: '',
    allDay: false,
    startDate: null,
    startTime: '09:00',
    endDate: null,
    endTime: '10:00',
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
    header="予定を追加"
    :style="{ width: '400px' }"
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
          autofocus
        />
        <small v-if="fieldErrors.title" class="text-red-500">{{ fieldErrors.title }}</small>
      </div>
      <div class="flex items-center gap-2">
        <ToggleSwitch v-model="form.allDay" />
        <label class="text-sm">終日</label>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">開始日</label>
          <DatePicker v-model="form.startDate" date-format="yy/mm/dd" class="w-full" show-icon />
        </div>
        <div v-if="!form.allDay">
          <label class="mb-1 block text-sm font-medium">開始時刻</label>
          <InputText v-model="form.startTime" type="time" class="w-full" />
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">終了日</label>
          <DatePicker v-model="form.endDate" date-format="yy/mm/dd" class="w-full" show-icon />
        </div>
        <div v-if="!form.allDay">
          <label class="mb-1 block text-sm font-medium">終了時刻</label>
          <InputText v-model="form.endTime" type="time" class="w-full" />
        </div>
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button label="追加" icon="pi pi-check" :loading="submitting" @click="submit" />
    </template>
  </Dialog>
</template>
