<script setup lang="ts">
import type { WorkflowCommentResponse } from '~/types/workflow'

defineProps<{
  comments: WorkflowCommentResponse[]
  submitting: boolean
}>()

const emit = defineEmits<{
  add: [body: string]
  delete: [commentId: number]
}>()

const { formatDateTime } = useWorkflowStatus()

const newComment = ref('')

function onAdd() {
  if (!newComment.value.trim()) return
  emit('add', newComment.value.trim())
  newComment.value = ''
}

function onDelete(commentId: number) {
  if (!confirm('このコメントを削除しますか？')) return
  emit('delete', commentId)
}
</script>

<template>
  <Card>
    <template #title>コメント ({{ comments.length }})</template>
    <template #content>
      <div class="space-y-3">
        <div v-for="comment in comments" :key="comment.id" class="rounded-lg border p-3">
          <div class="flex items-center justify-between">
            <span class="text-sm font-medium">ユーザー #{{ comment.userId }}</span>
            <div class="flex items-center gap-2">
              <span class="text-xs text-surface-500">{{
                formatDateTime(comment.createdAt)
              }}</span>
              <Button
                icon="pi pi-trash"
                text
                rounded
                size="small"
                severity="danger"
                @click="onDelete(comment.id)"
              />
            </div>
          </div>
          <p class="mt-1 whitespace-pre-wrap text-sm">{{ comment.body }}</p>
        </div>
      </div>

      <div class="mt-4 flex gap-2">
        <InputText
          v-model="newComment"
          class="flex-1"
          placeholder="コメントを入力..."
          @keyup.enter="onAdd"
        />
        <Button
          label="送信"
          icon="pi pi-send"
          :loading="submitting"
          :disabled="!newComment.trim()"
          @click="onAdd"
        />
      </div>
    </template>
  </Card>
</template>
