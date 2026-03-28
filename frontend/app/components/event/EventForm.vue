<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
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

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.eventId)

const form = ref({
  subtitle: '',
  slug: '',
  summary: '',
  venueName: '',
  venueAddress: '',
  isPublic: true,
  maxCapacity: null as number | null,
  isApprovalRequired: false,
  registrationStartsAt: null as Date | null,
  registrationEndsAt: null as Date | null,
})

watch(
  () => [props.visible, props.eventId],
  async ([visible, eventId]) => {
    if (visible && eventId) {
      try {
        const res = await eventApi.getEvent(props.scopeType, props.scopeId, eventId as number)
        const d = res.data
        form.value.subtitle = d.subtitle ?? ''
        form.value.slug = d.slug ?? ''
        form.value.summary = d.summary ?? ''
        form.value.venueName = d.venueName ?? ''
        form.value.venueAddress = d.venueAddress ?? ''
        form.value.isPublic = d.isPublic
        form.value.maxCapacity = d.maxCapacity
        form.value.isApprovalRequired = d.isApprovalRequired
        form.value.registrationStartsAt = d.registrationStartsAt
          ? new Date(d.registrationStartsAt)
          : null
        form.value.registrationEndsAt = d.registrationEndsAt ? new Date(d.registrationEndsAt) : null
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
    isPublic: form.value.isPublic,
    maxCapacity: form.value.maxCapacity || undefined,
    isApprovalRequired: form.value.isApprovalRequired,
    registrationStartsAt: form.value.registrationStartsAt
      ? form.value.registrationStartsAt.toISOString()
      : undefined,
    registrationEndsAt: form.value.registrationEndsAt
      ? form.value.registrationEndsAt.toISOString()
      : undefined,
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
      handleApiError(error)
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
    isPublic: true,
    maxCapacity: null,
    isApprovalRequired: false,
    registrationStartsAt: null,
    registrationEndsAt: null,
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
          <label class="mb-1 block text-sm font-medium">受付開始日時</label>
          <DatePicker
            v-model="form.registrationStartsAt"
            show-time
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">受付終了日時</label>
          <DatePicker
            v-model="form.registrationEndsAt"
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
            <Checkbox v-model="form.isPublic" :binary="true" input-id="isPublic" />
            <label for="isPublic" class="text-sm">一般公開</label>
          </div>
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
