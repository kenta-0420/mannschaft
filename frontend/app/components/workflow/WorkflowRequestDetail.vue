<script setup lang="ts">
import type { WorkflowCommentResponse, WorkflowRequestResponse } from '~/types/workflow'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  requestId: number
}>()

const workflowApi = useWorkflowApi()
const notification = useNotification()
const { statusLabel, statusSeverity, formatDateTime } = useWorkflowStatus()
const authStore = useAuthStore()

const request = ref<WorkflowRequestResponse | null>(null)
const comments = ref<WorkflowCommentResponse[]>([])
const loading = ref(true)
const submittingComment = ref(false)

const showDecideDialog = ref(false)
const decisionType = ref<'APPROVED' | 'REJECTED'>('APPROVED')
const submittingDecision = ref(false)

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
  showDecideDialog.value = true
}

async function submitDecision(comment: string, sealId: number | undefined) {
  submittingDecision.value = true
  try {
    await workflowApi.decideRequest(props.requestId, {
      decision: decisionType.value,
      comment: comment || undefined,
      sealId: sealId,
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

async function addComment(body: string) {
  submittingComment.value = true
  try {
    await workflowApi.addComment(props.requestId, { body })
    notification.success('コメントを追加しました')
    await loadComments()
  } catch {
    notification.error('コメント追加に失敗しました')
  } finally {
    submittingComment.value = false
  }
}

async function deleteComment(commentId: number) {
  try {
    await workflowApi.deleteComment(props.requestId, commentId)
    notification.success('コメントを削除しました')
    await loadComments()
  } catch {
    notification.error('削除に失敗しました')
  }
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

      <WorkflowApprovalSteps :steps="request.steps ?? []" />

      <!-- フィールド値 -->
      <Card v-if="request.fieldValues" class="mb-4">
        <template #title>入力内容</template>
        <template #content>
          <pre class="overflow-auto rounded bg-surface-100 p-3 text-sm dark:bg-surface-800">{{
            request.fieldValues
          }}</pre>
        </template>
      </Card>

      <WorkflowCommentSection
        :comments="comments"
        :submitting="submittingComment"
        @add="addComment"
        @delete="deleteComment"
      />

      <WorkflowDecisionDialog
        v-model:visible="showDecideDialog"
        :decision-type="decisionType"
        :submitting="submittingDecision"
        :is-seal-required="request?.isSealRequired"
        :user-id="authStore.user?.id"
        @submit="submitDecision"
      />
    </div>
  </div>
</template>
