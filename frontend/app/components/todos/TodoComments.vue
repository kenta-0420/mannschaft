<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  todoId: number
}>()

const todoApi = useTodoApi()
const authStore = useAuthStore()
const notification = useNotification()

interface Comment {
  id: number
  todoId: number
  userId: number
  displayName: string
  avatarUrl: string | null
  body: string
  createdAt: string
  updatedAt: string
}

const comments = ref<Comment[]>([])
const loading = ref(true)
const newComment = ref('')
const submitting = ref(false)
const editingId = ref<number | null>(null)
const editBody = ref('')

async function loadComments() {
  loading.value = true
  try {
    const res = await todoApi.getComments(props.scopeType, props.scopeId, props.todoId)
    comments.value = res.data
  }
  catch { comments.value = [] }
  finally { loading.value = false }
}

async function submitComment() {
  if (!newComment.value.trim()) return
  submitting.value = true
  try {
    await todoApi.addComment(props.scopeType, props.scopeId, props.todoId, newComment.value.trim())
    newComment.value = ''
    await loadComments()
  }
  catch { notification.error('コメントの投稿に失敗しました') }
  finally { submitting.value = false }
}

function startEdit(comment: Comment) {
  editingId.value = comment.id
  editBody.value = comment.body
}

async function saveEdit(commentId: number) {
  if (!editBody.value.trim()) return
  try {
    await todoApi.updateComment(props.scopeType, props.scopeId, props.todoId, commentId, editBody.value.trim())
    editingId.value = null
    await loadComments()
  }
  catch { notification.error('コメントの更新に失敗しました') }
}

async function deleteComment(commentId: number) {
  if (!confirm('このコメントを削除しますか？')) return
  try {
    await todoApi.deleteComment(props.scopeType, props.scopeId, props.todoId, commentId)
    notification.success('コメントを削除しました')
    await loadComments()
  }
  catch { notification.error('コメントの削除に失敗しました') }
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('ja-JP', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

onMounted(loadComments)
</script>

<template>
  <div>
    <h4 class="mb-3 text-sm font-semibold">コメント</h4>

    <!-- コメント一覧 -->
    <div v-if="loading" class="space-y-3">
      <Skeleton height="3rem" />
      <Skeleton height="3rem" />
    </div>
    <div v-else-if="comments.length > 0" class="space-y-3">
      <div v-for="comment in comments" :key="comment.id" class="flex gap-3">
        <Avatar
          :image="comment.avatarUrl"
          :label="comment.avatarUrl ? undefined : comment.displayName.charAt(0)"
          shape="circle"
          size="normal"
        />
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium">{{ comment.displayName }}</span>
            <span class="text-xs text-surface-400">{{ formatDate(comment.createdAt) }}</span>
          </div>
          <!-- 編集モード -->
          <div v-if="editingId === comment.id" class="mt-1">
            <Textarea v-model="editBody" rows="2" class="w-full" auto-resize />
            <div class="mt-1 flex gap-1">
              <Button label="保存" size="small" @click="saveEdit(comment.id)" />
              <Button label="キャンセル" size="small" text @click="editingId = null" />
            </div>
          </div>
          <!-- 表示モード -->
          <div v-else>
            <p class="mt-1 whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300">{{ comment.body }}</p>
            <div v-if="comment.userId === authStore.currentUser?.id" class="mt-1 flex gap-1">
              <Button icon="pi pi-pencil" text rounded size="small" @click="startEdit(comment)" />
              <Button icon="pi pi-trash" text rounded size="small" severity="danger" @click="deleteComment(comment.id)" />
            </div>
          </div>
        </div>
      </div>
    </div>
    <p v-else class="text-sm text-surface-400">コメントはまだありません</p>

    <!-- 投稿フォーム -->
    <div class="mt-4 flex gap-2">
      <Textarea v-model="newComment" rows="1" class="flex-1" placeholder="コメントを入力..." auto-resize />
      <Button icon="pi pi-send" :loading="submitting" :disabled="!newComment.trim()" @click="submitComment" />
    </div>
  </div>
</template>
