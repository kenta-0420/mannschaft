<script setup lang="ts">
import type { ReportResponse, InternalNoteResponse } from '~/types/admin-report'

defineProps<{
  report: ReportResponse | null
  notes: InternalNoteResponse[]
  statusSeverity: (status: string) => string
}>()

const visible = defineModel<boolean>('visible', { required: true })
const newNote = defineModel<string>('newNote', { required: true })

const { formatDateTime } = useDatetime()

const emit = defineEmits<{
  addNote: []
  hideContent: [id: number]
  restoreContent: [id: number]
}>()
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('admin_report.detail.header')"
    :style="{ width: '700px' }"
    modal
  >
    <div v-if="report" class="flex flex-col gap-4">
      <div class="grid grid-cols-2 gap-3">
        <div>
          <p class="text-xs text-surface-500">{{ $t('admin_report.detail.target_type') }}</p>
          <p>{{ report.targetType }}</p>
        </div>
        <div>
          <p class="text-xs text-surface-500">{{ $t('admin_report.detail.status') }}</p>
          <Tag :value="report.status" :severity="statusSeverity(report.status)" />
        </div>
        <div>
          <p class="text-xs text-surface-500">{{ $t('admin_report.detail.reason') }}</p>
          <p>{{ report.reason }}</p>
        </div>
        <div>
          <p class="text-xs text-surface-500">{{ $t('admin_report.detail.reported_at') }}</p>
          <p class="text-sm">{{ formatDateTime(report.createdAt) }}</p>
        </div>
      </div>
      <div v-if="report.description">
        <p class="text-xs text-surface-500">{{ $t('admin_report.detail.description') }}</p>
        <p class="text-sm">{{ report.description }}</p>
      </div>
      <div class="flex gap-2">
        <Button
          :label="$t('admin_report.detail.hide_content')"
          size="small"
          severity="warn"
          @click="emit('hideContent', report.id)"
        />
        <Button
          :label="$t('admin_report.detail.restore_content')"
          size="small"
          severity="info"
          @click="emit('restoreContent', report.id)"
        />
      </div>

      <Divider />
      <h3 class="text-sm font-semibold">{{ $t('admin_report.detail.internal_notes') }}</h3>
      <div class="max-h-40 space-y-2 overflow-y-auto">
        <div v-for="note in notes" :key="note.id" class="rounded border border-surface-300 p-2">
          <p class="text-sm">{{ note.note }}</p>
          <p class="text-xs text-surface-400">
            {{ formatDateTime(note.createdAt) }}
          </p>
        </div>
        <p v-if="notes.length === 0" class="text-sm text-surface-400">{{ $t('admin_report.detail.no_notes') }}</p>
      </div>
      <div class="flex gap-2">
        <InputText v-model="newNote" :placeholder="$t('admin_report.detail.note_placeholder')" class="flex-1" />
        <Button :label="$t('admin_report.detail.add_note')" size="small" @click="emit('addNote')" />
      </div>
    </div>
  </Dialog>
</template>
