<script setup lang="ts">
import type { BulletinThreadResponse, BulletinReplyResponse } from '~/types/bulletin'

const props = defineProps<{
  threadId: number
  canManage?: boolean
}>()

const emit = defineEmits<{
  back: []
}>()

const { getThread, markRead, createReply, togglePin, toggleLock, deleteThread } = useBulletinApi()
const { showSuccess, showError } = useNotification()
const { relativeTime } = useRelativeTime()

const thread = ref<(BulletinThreadResponse & { replies: BulletinReplyResponse[] }) | null>(null)
const replyBody = ref('')
const submitting = ref(false)

async function loadThread() {
  try {
    const res = await getThread(props.threadId)
    thread.value = res.data
    markRead(props.threadId)
  } catch {
    showError('スレッドの取得に失敗しました')
  }
}

async function onReply() {
  if (!replyBody.value.trim() || submitting.value) return
  submitting.value = true
  try {
    const res = await createReply(props.threadId, replyBody.value.trim())
    thread.value?.replies.push(res.data)
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
    <div class="rounded-xl border border-surface-200 bg-surface-0 p-6">
      <h2 class="mb-2 text-xl font-bold">{{ thread.title }}</h2>
      <div class="mb-4 flex items-center gap-3 text-sm text-surface-400">
        <Avatar :label="thread.author.displayName.charAt(0)" shape="circle" size="small" />
        <span>{{ thread.author.displayName }}</span>
        <span>{{ relativeTime(thread.createdAt) }}</span>
        <span v-if="thread.readTrackingMode !== 'NONE'"><i class="pi pi-eye" /> {{ thread.readCount }}人既読</span>
      </div>
      <div class="prose max-w-none text-sm leading-relaxed" v-html="thread.body" />
    </div>

    <!-- 返信一覧 -->
    <div class="mt-6">
      <h3 class="mb-3 text-sm font-semibold text-surface-500">返信 {{ thread.replyCount }}件</h3>
      <div class="flex flex-col gap-3">
        <div
          v-for="reply in thread.replies"
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
    <div v-if="!thread.isLocked" class="mt-4 rounded-xl border border-surface-200 bg-surface-0 p-4">
      <Textarea v-model="replyBody" placeholder="返信を入力..." auto-resize rows="2" class="mb-2 w-full" />
      <div class="flex justify-end">
        <Button label="返信" size="small" :loading="submitting" :disabled="!replyBody.trim()" @click="onReply" />
      </div>
    </div>
    <div v-else class="mt-4 rounded-lg bg-surface-100 p-3 text-center text-sm text-surface-400">
      <i class="pi pi-lock" /> このスレッドはロックされています
    </div>
  </div>

  <div v-else class="flex justify-center py-12">
    <ProgressSpinner style="width: 40px; height: 40px" />
  </div>
</template>
