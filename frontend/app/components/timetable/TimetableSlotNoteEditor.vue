<script setup lang="ts">
/**
 * F03.15 Phase 3 個人メモエディタ。
 *
 * 標準4項目（preparation / review / items_to_bring / free_memo）+ カスタムフィールド +
 * 添付ファイル（presign 経由 R2 直 PUT）を扱うコアコンポーネント。
 */
import type {
  Attachment,
  TimetableSlotKind,
  TimetableSlotUserNote,
  TimetableSlotUserNoteField,
} from '~/types/timetable-note'

const props = defineProps<{
  slotKind: TimetableSlotKind
  slotId: number
  targetDate?: string | null
}>()

const emit = defineEmits<{
  (e: 'saved', note: TimetableSlotUserNote): void
  (e: 'closed'): void
}>()

const { t } = useI18n()
const { error, success } = useNotification()
const api = useTimetableSlotNoteApi()

const loading = ref(true)
const saving = ref(false)
const note = ref<TimetableSlotUserNote | null>(null)
const fields = ref<TimetableSlotUserNoteField[]>([])
const attachments = ref<Attachment[]>([])

const form = reactive({
  preparation: '',
  review: '',
  itemsToBring: '',
  freeMemo: '',
  customFields: {} as Record<number, string>,
})

async function load() {
  loading.value = true
  try {
    const [notes, customFields] = await Promise.all([
      api.listNotes(props.slotKind, props.slotId, props.targetDate ?? undefined),
      api.listFields(),
    ])
    fields.value = customFields
    note.value = notes.length > 0 ? notes[0]! : null
    if (note.value) {
      form.preparation = note.value.preparation ?? ''
      form.review = note.value.review ?? ''
      form.itemsToBring = note.value.items_to_bring ?? ''
      form.freeMemo = note.value.free_memo ?? ''
      const cfs = note.value.custom_fields ?? []
      for (const cf of cfs) {
        form.customFields[cf.field_id] = cf.value ?? ''
      }
      await loadAttachments()
    }
  }
  catch (e) {
    error(t('personalTimetable.notes.load_error'), String(e))
  }
  finally {
    loading.value = false
  }
}

async function loadAttachments() {
  if (!note.value) {
    attachments.value = []
    return
  }
  try {
    attachments.value = await api.listAttachments(note.value.id)
  }
  catch {
    attachments.value = []
  }
}

async function save() {
  saving.value = true
  try {
    const customFields = Object.entries(form.customFields)
      .filter(([_, v]) => v !== '')
      .map(([k, v]) => ({ field_id: Number(k), value: v }))
    const ifUnmodifiedSince = note.value?.updated_at
    const saved = await api.upsertNote(
      {
        slot_kind: props.slotKind,
        slot_id: props.slotId,
        target_date: props.targetDate ?? null,
        preparation: form.preparation || null,
        review: form.review || null,
        items_to_bring: form.itemsToBring || null,
        free_memo: form.freeMemo || null,
        custom_fields: customFields.length > 0 ? customFields : null,
      },
      ifUnmodifiedSince ? new Date(ifUnmodifiedSince).toUTCString() : undefined,
    )
    note.value = saved
    success(t('personalTimetable.notes.save_success'))
    emit('saved', saved)
  }
  catch (e) {
    error(t('personalTimetable.notes.save_error'), String(e))
  }
  finally {
    saving.value = false
  }
}

async function uploadAttachment(file: File) {
  if (!note.value) {
    error(t('personalTimetable.notes.save_first'))
    return
  }
  try {
    const presign = await api.presignAttachment(note.value.id, {
      file_name: file.name,
      content_type: file.type,
      size_bytes: file.size,
    })
    await fetch(presign.upload_url, {
      method: 'PUT',
      headers: { 'Content-Type': file.type },
      body: file,
    })
    await api.confirmAttachment(note.value.id, {
      r2_object_key: presign.r2_object_key,
      file_name: file.name,
      content_type: file.type,
      size_bytes: file.size,
    })
    await loadAttachments()
    success(t('personalTimetable.notes.attachment_upload_success'))
  }
  catch (e) {
    error(t('personalTimetable.notes.attachment_upload_error'), String(e))
  }
}

function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  if (!target.files || target.files.length === 0) return
  uploadAttachment(target.files[0]!)
  target.value = ''
}

async function downloadAttachment(att: Attachment) {
  try {
    const result = await api.getAttachmentDownloadUrl(att.id)
    window.open(result.download_url, '_blank', 'noopener')
  }
  catch (e) {
    error(t('personalTimetable.notes.attachment_download_error'), String(e))
  }
}

async function removeAttachment(att: Attachment) {
  try {
    await api.deleteAttachment(att.id)
    await loadAttachments()
  }
  catch (e) {
    error(t('personalTimetable.notes.attachment_delete_error'), String(e))
  }
}

watch(() => [props.slotKind, props.slotId, props.targetDate], load, { immediate: true })
</script>

<template>
  <div class="space-y-3">
    <p v-if="loading" class="text-sm text-gray-500">
      {{ t('personalTimetable.notes.loading') }}
    </p>
    <template v-else>
      <div>
        <label class="block text-sm font-medium mb-1">{{ t('personalTimetable.notes.field_preparation') }}</label>
        <textarea
          v-model="form.preparation"
          class="w-full rounded border border-gray-300 px-2 py-1"
          rows="3"
          :maxlength="2000"
        />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">{{ t('personalTimetable.notes.field_review') }}</label>
        <textarea
          v-model="form.review"
          class="w-full rounded border border-gray-300 px-2 py-1"
          rows="3"
          :maxlength="2000"
        />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">{{ t('personalTimetable.notes.field_items_to_bring') }}</label>
        <textarea
          v-model="form.itemsToBring"
          class="w-full rounded border border-gray-300 px-2 py-1"
          rows="2"
          :maxlength="2000"
        />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">{{ t('personalTimetable.notes.field_free_memo') }}</label>
        <textarea
          v-model="form.freeMemo"
          class="w-full rounded border border-gray-300 px-2 py-1"
          rows="5"
          :maxlength="10000"
        />
      </div>
      <div v-for="f in fields" :key="f.id">
        <label class="block text-sm font-medium mb-1">{{ f.label }}</label>
        <textarea
          v-model="form.customFields[f.id]"
          class="w-full rounded border border-gray-300 px-2 py-1"
          rows="2"
          :placeholder="f.placeholder ?? ''"
          :maxlength="f.maxLength"
        />
      </div>

      <div class="flex flex-wrap gap-2 pt-2">
        <button
          type="button"
          class="rounded bg-blue-600 px-3 py-1 text-white text-sm disabled:opacity-50"
          :disabled="saving"
          @click="save"
        >
          {{ t('personalTimetable.notes.btn_save') }}
        </button>
        <button
          type="button"
          class="rounded bg-gray-300 px-3 py-1 text-sm"
          @click="emit('closed')"
        >
          {{ t('personalTimetable.btn_cancel') }}
        </button>
      </div>

      <div v-if="note" class="border-t pt-3 mt-2">
        <h4 class="text-sm font-bold mb-2">
          {{ t('personalTimetable.notes.attachments_title') }}
        </h4>
        <ul v-if="attachments.length > 0" class="space-y-1 mb-2">
          <li
            v-for="att in attachments"
            :key="att.id"
            class="flex items-center gap-2 text-sm"
          >
            <button
              type="button"
              class="text-blue-600 hover:underline"
              @click="downloadAttachment(att)"
            >
              {{ att.original_filename }}
            </button>
            <span class="text-gray-500 text-xs">
              {{ Math.round(att.size_bytes / 1024) }} KB
            </span>
            <button
              type="button"
              class="ml-auto text-red-600 hover:underline text-xs"
              @click="removeAttachment(att)"
            >
              {{ t('personalTimetable.btn_delete') }}
            </button>
          </li>
        </ul>
        <input
          type="file"
          class="text-sm"
          accept="image/jpeg,image/png,image/webp,image/heic,application/pdf"
          @change="onFileChange"
        >
      </div>
    </template>
  </div>
</template>
