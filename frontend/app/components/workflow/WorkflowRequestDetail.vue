<script setup lang="ts">
import type { WorkflowCommentResponse, WorkflowRequestResponse } from '~/types/workflow'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  requestId: number
}>()

const workflowApi = useWorkflowApi()
const notification = useNotification()

const request = ref<WorkflowRequestResponse | null>(null)
const comments = ref<WorkflowCommentResponse[]>([])
const loading = ref(true)
const newComment = ref('')
const submittingComment = ref(false)

// 承認/却下
const showDecideDialog = ref(false)
const decisionType = ref<'APPROVED' | 'REJECTED'>('APPROVED')
const decisionComment = ref('')
const submittingDecision = ref(false)

function statusSeverity(status: string) {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'SUBMITTED':
      return 'info'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
      return 'warn'
    default:
      return 'info'
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT':
      return '下書き'
    case 'SUBMITTED':
      return '申請中'
    case 'APPROVED':
      return '承認済'
    case 'REJECTED':
      return '却下'
    case 'WITHDRAWN':
      return '取下げ'
    case 'PENDING':
      return '保留中'
    case 'COMPLETED':
      return '完了'
    default:
      return status
  }
}

function decisionLabel(decision: string | null) {
  switch (decision) {
    case 'APPROVED':
      return '承認'
    case 'REJECTED':
      return '却下'
    default:
      return '未対応'
  }
}

async function loadRequest() {
  loading.value = true
  try {
    const res = await workflowApi.getRequest(props.scopeType, props.scopeId, props.requestId)
    request.value = res.data
  } catch {
    notification.error('申請情報の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function loadComments() {
  try {
    const res = await workflowApi.listComments(props.requestId)
    comments.value = res.data
  } catch {
    comments.value = []
  }
}

async function onSubmitRequest() {
  if (!confirm('この申請を提出しますか？')) return
  try {
    await workflowApi.submitRequest(props.scopeType, props.scopeId, props.requestId)
    notification.success('申請を提出しました')
    await loadRequest()
  } catch {
    notification.error('提出に失敗しました')
  }
}

async function onWithdraw() {
  if (!confirm('この申請を取り下げますか？')) return
  try {
    await workflowApi.withdrawRequest(props.scopeType, props.scopeId, props.requestId)
    notification.success('申請を取り下げました')
    await loadRequest()
  } catch {
    notification.error('取下げに失敗しました')
  }
}

function openDecide(type: 'APPROVED' | 'REJECTED') {
  decisionType.value = type
  decisionComment.value = ''
  showDecideDialog.value = true
}

async function submitDecision() {
  submittingDecision.value = true
  try {
    await workflowApi.decideRequest(props.requestId, {
      decision: decisionType.value,
      comment: decisionComment.value.trim() || undefined,
    })
    notification.success(decisionType.value === 'APPROVED' ? '承認しました' : '却下しました')
    showDecideDialog.value = false
    await loadRequest()
  } catch {
    notification.error('処理に失敗しました')
  } finally {
    submittingDecision.value = false
  }
}

async function addComment() {
  if (!newComment.value.trim()) return
  submittingComment.value = true
  try {
    await workflowApi.addComment(props.requestId, { body: newComment.value.trim() })
    notification.success('コメントを追加しました')
    newComment.value = ''
    await loadComments()
  } catch {
    notification.error('コメント追加に失敗しました')
  } finally {
    submittingComment.value = false
  }
}

async function deleteComment(commentId: number) {
  if (!confirm('このコメントを削除しますか？')) return
  try {
    await workflowApi.deleteComment(props.requestId, commentId)
    notification.success('コメントを削除しました')
    await loadComments()
  } catch {
    notification.error('削除に失敗しました')
  }
}

function formatDateTime(dateStr: string | null): string {
  if (!dateStr) return '—'
  return new Date(dateStr).toLocaleString('ja-JP')
}

onMounted(async () => {
  await loadRequest()
  await loadComments()
})
</script>

<template>
  <div>
    <div v-if="loading" class="flex items-center justify-center py-12">
      <ProgressSpinner />
    </div>

    <div v-else-if="request">
      <!-- ヘッダー -->
      <div class="mb-6 flex items-start justify-between">
        <div>
          <h1 class="text-2xl font-bold">{{ request.title || `申請 #${request.id}` }}</h1>
          <div class="mt-2 flex items-center gap-3">
            <Tag :value="statusLabel(request.status)" :severity="statusSeverity(request.status)" />
            <span v-if="request.requestedAt" class="text-sm text-surface-500"
              >申請日: {{ formatDateTime(request.requestedAt) }}</span
            >
          </div>
        </div>
        <div class="flex gap-2">
          <Button
            v-if="request.status === 'DRAFT'"
            label="提出"
            icon="pi pi-send"
            severity="success"
            @click="onSubmitRequest"
          />
          <Button
            v-if="request.status === 'SUBMITTED'"
            label="取下げ"
            icon="pi pi-undo"
            severity="warn"
            outlined
            @click="onWithdraw"
          />
          <Button
            v-if="request.status === 'SUBMITTED'"
            label="承認"
            icon="pi pi-check"
            severity="success"
            @click="openDecide('APPROVED')"
          />
          <Button
            v-if="request.status === 'SUBMITTED'"
            label="却下"
            icon="pi pi-times"
            severity="danger"
            outlined
            @click="openDecide('REJECTED')"
          />
        </div>
      </div>

      <!-- 承認フロー -->
      <Card class="mb-4">
        <template #title>承認フロー</template>
        <template #content>
          <div v-if="request.steps && request.steps.length > 0" class="space-y-4">
            <div
              v-for="step in request.steps"
              :key="step.id"
              class="flex items-start gap-4 rounded-lg border p-3"
            >
              <div
                class="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-primary/10 font-bold text-primary"
              >
                {{ step.stepOrder }}
              </div>
              <div class="flex-1">
                <div class="flex items-center gap-2">
                  <Tag :value="statusLabel(step.status)" :severity="statusSeverity(step.status)" />
                  <span v-if="step.completedAt" class="text-sm text-surface-500">{{
                    formatDateTime(step.completedAt)
                  }}</span>
                </div>
                <div v-if="step.approvers && step.approvers.length > 0" class="mt-2 space-y-1">
                  <div v-for="approver in step.approvers" :key="approver.id" class="text-sm">
                    <span class="text-surface-500">承認者 #{{ approver.approverUserId }}:</span>
                    <span class="ml-1 font-medium">{{ decisionLabel(approver.decision) }}</span>
                    <span v-if="approver.decisionComment" class="ml-2 text-surface-500"
                      >「{{ approver.decisionComment }}」</span
                    >
                  </div>
                </div>
              </div>
            </div>
          </div>
          <p v-else class="text-surface-500">承認ステップはありません</p>
        </template>
      </Card>

      <!-- フィールド値 -->
      <Card v-if="request.fieldValues" class="mb-4">
        <template #title>入力内容</template>
        <template #content>
          <pre class="overflow-auto rounded bg-surface-100 p-3 text-sm dark:bg-surface-800">{{
            request.fieldValues
          }}</pre>
        </template>
      </Card>

      <!-- コメント -->
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
                    @click="deleteComment(comment.id)"
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
              @keyup.enter="addComment"
            />
            <Button
              label="送信"
              icon="pi pi-send"
              :loading="submittingComment"
              :disabled="!newComment.trim()"
              @click="addComment"
            />
          </div>
        </template>
      </Card>

      <!-- 承認/却下ダイアログ -->
      <Dialog
        v-model:visible="showDecideDialog"
        :header="decisionType === 'APPROVED' ? '承認' : '却下'"
        :style="{ width: '400px' }"
        modal
      >
        <div class="flex flex-col gap-4">
          <p>
            {{
              decisionType === 'APPROVED' ? 'この申請を承認しますか？' : 'この申請を却下しますか？'
            }}
          </p>
          <div>
            <label class="mb-1 block text-sm font-medium">コメント</label>
            <Textarea
              v-model="decisionComment"
              rows="3"
              class="w-full"
              placeholder="コメント（任意）"
            />
          </div>
        </div>
        <template #footer>
          <Button label="キャンセル" text @click="showDecideDialog = false" />
          <Button
            :label="decisionType === 'APPROVED' ? '承認' : '却下'"
            :severity="decisionType === 'APPROVED' ? 'success' : 'danger'"
            :loading="submittingDecision"
            @click="submitDecision"
          />
        </template>
      </Dialog>
    </div>
  </div>
</template>
