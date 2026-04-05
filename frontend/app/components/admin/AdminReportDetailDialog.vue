<script setup lang="ts">
import type { ReportResponse, InternalNoteResponse } from '~/types/admin-report'

defineProps<{
  report: ReportResponse | null
  notes: InternalNoteResponse[]
  statusSeverity: (status: string) => string
}>()

const visible = defineModel<boolean>('visible', { required: true })
const newNote = defineModel<string>('newNote', { required: true })

const emit = defineEmits<{
  addNote: []
  hideContent: [id: number]
  restoreContent: [id: number]
}>()
</script>

<template>
  <Dialog
    v-model:visible="visible"
    header="レポート詳細"
    :style="{ width: '700px' }"
    modal
  >
    <div v-if="report" class="flex flex-col gap-4">
      <div class="grid grid-cols-2 gap-3">
        <div>
          <p class="text-xs text-surface-500">対象種別</p>
          <p>{{ report.targetType }}</p>
        </div>
        <div>
          <p class="text-xs text-surface-500">ステータス</p>
          <Tag :value="report.status" :severity="statusSeverity(report.status)" />
        </div>
        <div>
          <p class="text-xs text-surface-500">理由</p>
          <p>{{ report.reason }}</p>
        </div>
        <div>
          <p class="text-xs text-surface-500">報告日</p>
          <p class="text-sm">{{ new Date(report.createdAt).toLocaleString('ja-JP') }}</p>
        </div>
      </div>
      <div v-if="report.description">
        <p class="text-xs text-surface-500">詳細</p>
        <p class="text-sm">{{ report.description }}</p>
      </div>
      <div class="flex gap-2">
        <Button
          label="コンテンツ非表示"
          size="small"
          severity="warn"
          @click="emit('hideContent', report.id)"
        />
        <Button
          label="コンテンツ復元"
          size="small"
          severity="info"
          @click="emit('restoreContent', report.id)"
        />
      </div>

      <Divider />
      <h3 class="text-sm font-semibold">内部メモ</h3>
      <div class="max-h-40 space-y-2 overflow-y-auto">
        <div v-for="note in notes" :key="note.id" class="rounded border border-surface-300 p-2">
          <p class="text-sm">{{ note.note }}</p>
          <p class="text-xs text-surface-400">
            {{ new Date(note.createdAt).toLocaleString('ja-JP') }}
          </p>
        </div>
        <p v-if="notes.length === 0" class="text-sm text-surface-400">メモがありません</p>
      </div>
      <div class="flex gap-2">
        <InputText v-model="newNote" placeholder="メモを入力..." class="flex-1" />
        <Button label="追加" size="small" @click="emit('addNote')" />
      </div>
    </div>
  </Dialog>
</template>
