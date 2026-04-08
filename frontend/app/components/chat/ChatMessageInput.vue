<script setup lang="ts">
const props = defineProps<{
  channelId: number
  parentId?: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  sent: []
}>()

const { sendMessage } = useChatApi()
const { showError } = useNotification()

const body = ref('')
const sending = ref(false)

async function onSend() {
  if (!body.value.trim() || sending.value || props.disabled) return
  sending.value = true
  try {
    await sendMessage(props.channelId, body.value.trim(), props.parentId)
    body.value = ''
    emit('sent')
  } catch {
    showError('メッセージの送信に失敗しました')
  } finally {
    sending.value = false
  }
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    onSend()
  }
}
</script>

<template>
  <div class="border-t border-surface-200 bg-surface-0 p-3">
    <div class="flex items-end gap-2">
      <Textarea
        v-model="body"
        :placeholder="disabled ? 'このZimmer(部屋)には投稿できません' : 'メッセージを入力...'"
        auto-resize
        rows="1"
        class="flex-1"
        :disabled="disabled"
        @keydown="onKeydown"
      />
      <Button
        data-testid="chat-send-btn"
        icon="pi pi-send"
        :loading="sending"
        :disabled="!body.trim() || disabled"
        @click="onSend"
      />
    </div>
  </div>
</template>
