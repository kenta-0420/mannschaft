<script setup lang="ts">
import type { BulletinThreadResponse, BulletinReplyResponse, BulletinAttachment } from '~/types/bulletin'

const props = defineProps<{
  threadId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  back: []
}>()

const {
  getThread,
  markRead,
  getReplies,
  createReply,
  togglePin,
  toggleLock,
  deleteThread,
  listThreadAttachments,
  getAttachmentDownloadUrl,
  deleteAttachment,
} = useBulletinApi()
const { showSuccess, showError } = useNotification()
const { relativeTime } = useRelativeTime()
const { t } = useI18n()
const authStore = useAuthStore()

const thread = ref<BulletinThreadResponse | null>(null)
/** 返信一覧（スレッド詳細とは別APIで取得: GET /api/v1/bulletin/threads/{threadId}/replies） */
const replies = ref<BulletinReplyResponse[]>([])
/** 添付ファイル一覧 */
const attachments = ref<BulletinAttachment[]>([])
const replyBody = ref('')
const submitting = ref(false)
/** ダウンロード中の attachment id セット */
const downloadingIds = ref<Set<number>>(new Set())
/** 削除確認中の attachment id */
const deletingId = ref<number | null>(null)

/** 現在のユーザー ID */
const currentUserId = computed(() => authStore.currentUser?.id ?? null)

/** 本人 or モデレーター判定（削除ボタン表示に使用） */
function canDeleteAttachment(attachment: BulletinAttachment): boolean {
  return props.canManage === true || attachment.createdBy === currentUserId.value
}

async function loadThread() {
  try {
    const [threadRes, repliesRes, attachmentsRes] = await Promise.all([
      getThread(props.threadId),
      getReplies(props.threadId),
      listThreadAttachments(props.threadId),
    ])
    thread.value = threadRes.data
    replies.value = repliesRes.data ?? []
    attachments.value = attachmentsRes
    markRead(props.threadId)
  } catch {
    showError('スレッドの取得に失敗しました')
  }
}

async function onDownloadAttachment(attachment: BulletinAttachment) {
  if (downloadingIds.value.has(attachment.id)) return
  downloadingIds.value = new Set([...downloadingIds.value, attachment.id])
  try {
    const { downloadUrl } = await getAttachmentDownloadUrl(attachment.id)
    // presigned URL を新タブで開いてダウンロード
    window.open(downloadUrl, '_blank', 'noopener,noreferrer')
  } catch {
    showError(t('bulletin.attachment.downloadFailed'))
  } finally {
    downloadingIds.value = new Set([...downloadingIds.value].filter(id => id !== attachment.id))
  }
}

async function onDeleteAttachment(attachment: BulletinAttachment) {
  if (deletingId.value !== null) return
  deletingId.value = attachment.id
  try {
    await deleteAttachment(attachment.id)
    attachments.value = attachments.value.filter(a => a.id !== attachment.id)
    showSuccess(t('bulletin.attachment.deleteSuccess'))
  } catch {
    showError(t('bulletin.attachment.deleteFailed'))
  } finally {
    deletingId.value = null
  }
}

async function onReply() {
  if (!replyBody.value.trim() || submitting.value) return
  submitting.value = true
  try {
    const res = await createReply(props.threadId, replyBody.value.trim())
    replies.value.push(res.data)
    if (thread.value) thread.value.replyCount++
    replyBody.value = ''
    showSuccess('返信しました')
  } catch {
    showError('返信に失敗しました')
  } finally {
    submitting.value = false
  }
}

async function onPin() {
  if (!thread.value) return
  try {
    await togglePin(props.threadId, !thread.value.isPinned)
    thread.value.isPinned = !thread.value.isPinned
    showSuccess(thread.value.isPinned ? 'ピン留めしました' : 'ピン解除しました')
  } catch {
    showError('操作に失敗しました')
  }
}

async function onLock() {
  if (!thread.value) return
  try {
    await toggleLock(props.threadId, !thread.value.isLocked)
    thread.value.isLocked = !thread.value.isLocked
    showSuccess(thread.value.isLocked ? 'ロックしました' : 'ロック解除しました')
  } catch {
    showError('操作に失敗しました')
  }
}

async function onDelete() {
  try {
    await deleteThread(props.threadId)
    showSuccess('スレッドを削除しました')
    emit('back')
  } catch {
    showError('削除に失敗しました')
  }
}

onMounted(() => loadThread())
watch(() => props.threadId, () => loadThread())
</script>

<template>
  <div v-if="thread">
    <!-- 戻るボタン + アクション -->
    <div class="mb-4 flex items-center justify-between">
      <Button icon="pi pi-arrow-left" label="一覧へ戻る" text size="small" @click="emit('back')" />
      <div v-if="canManage" class="flex items-center gap-1">
        <Button :icon="thread.isPinned ? 'pi pi-thumbtack' : 'pi pi-thumbtack'" :label="thread.isPinned ? 'ピン解除' : 'ピン留め'" text size="small" @click="onPin" />
        <Button :icon="thread.isLocked ? 'pi pi-unlock' : 'pi pi-lock'" :label="thread.isLocked ? 'ロック解除' : 'ロック'" text size="small" @click="onLock" />
        <Button icon="pi pi-trash" label="削除" text size="small" severity="danger" @click="onDelete" />
      </div>
    </div>

    <!-- スレッド本体 -->
    <SectionCard>
      <h2 class="mb-2 text-xl font-bold">{{ thread.title }}</h2>
      <div class="mb-4 flex items-center gap-3 text-sm text-surface-400">
        <Avatar :label="thread.author.displayName.charAt(0)" shape="circle" size="small" />
        <span>{{ thread.author.displayName }}</span>
        <span>{{ relativeTime(thread.createdAt) }}</span>
        <span v-if="thread.readTrackingMode !== 'NONE'"><i class="pi pi-eye" /> {{ thread.readCount }}人既読</span>
      </div>
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div class="prose max-w-none text-sm leading-relaxed" v-html="sanitizeHtml(thread.body)" />
    </SectionCard>

    <!-- 添付ファイル一覧 -->
    <div v-if="attachments.length > 0" class="mt-4">
      <h3 class="mb-2 text-sm font-semibold text-surface-500">
        <i class="pi pi-paperclip mr-1" />{{ $t('bulletin.attachment.label').split('（')[0] }}
      </h3>
      <div class="flex flex-col gap-2">
        <div
          v-for="attachment in attachments"
          :key="attachment.id"
          class="flex items-center gap-2 rounded border border-surface-200 bg-surface-50 px-3 py-2 text-sm dark:border-surface-700 dark:bg-surface-800"
        >
          <i class="pi pi-file text-surface-400" />
          <span class="flex-1 truncate">{{ attachment.originalFilename }}</span>
          <span class="text-surface-400">{{ (attachment.fileSize / 1024 / 1024).toFixed(1) }}MB</span>
          <Button
            :label="$t('bulletin.attachment.download')"
            icon="pi pi-download"
            text
            size="small"
            :loading="downloadingIds.has(attachment.id)"
            @click="onDownloadAttachment(attachment)"
          />
          <Button
            v-if="canDeleteAttachment(attachment)"
            :label="$t('bulletin.attachment.delete')"
            icon="pi pi-trash"
            text
            size="small"
            severity="danger"
            :loading="deletingId === attachment.id"
            @click="onDeleteAttachment(attachment)"
          />
        </div>
      </div>
    </div>

    <!-- 返信一覧 -->
    <div class="mt-6">
      <h3 class="mb-3 text-sm font-semibold text-surface-500">返信 {{ thread.replyCount }}件</h3>
      <div class="flex flex-col gap-3">
        <div
          v-for="reply in replies"
          :key="reply.id"
          class="rounded-lg border border-surface-100 bg-surface-0 p-4"
          :style="{ marginLeft: `${reply.depth * 24}px` }"
        >
          <div class="mb-2 flex items-center gap-2 text-xs text-surface-400">
            <span class="font-medium text-surface-600">{{ reply.author.displayName }}</span>
            <span>{{ relativeTime(reply.createdAt) }}</span>
          </div>
          <p class="whitespace-pre-wrap text-sm">{{ reply.body }}</p>
        </div>
      </div>
    </div>

    <!-- 返信フォーム -->
    <SectionCard v-if="!thread.isLocked" class="mt-4">
      <Textarea v-model="replyBody" placeholder="返信を入力..." auto-resize rows="2" class="mb-2 w-full" />
      <div class="flex justify-end">
        <Button label="返信" size="small" :loading="submitting" :disabled="!replyBody.trim()" @click="onReply" />
      </div>
    </SectionCard>
    <div v-else class="mt-4 rounded-lg bg-surface-100 p-3 text-center text-sm text-surface-400">
      <i class="pi pi-lock" /> このスレッドはロックされています
    </div>
  </div>

  <div v-else class="flex justify-center py-12">
    <LoadingBounce />
  </div>
</template>
