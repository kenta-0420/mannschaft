<script setup lang="ts">
const props = defineProps<{
  channelId: number
  parentId?: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  sent: []
  /** テキスト入力イベント（タイピングインジケーター用） */
  typing: []
}>()

const { sendMessage } = useChatApi()
const { showError } = useNotification()
const { t } = useI18n()

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
    showError(t('chat.message.sendFailed'))
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
  <div class="border-t border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-3">
    <div class="flex items-end gap-2">
      <Textarea
        v-model="body"
        :placeholder="disabled ? $t('chat.message.inputDisabled') : $t('chat.message.inputPlaceholder')"
        auto-resize
        rows="1"
        class="flex-1"
        :disabled="disabled"
        data-testid="team-chat-input"
        @keydown="onKeydown"
        @input="emit('typing')"
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
