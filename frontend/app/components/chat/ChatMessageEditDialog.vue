<script setup lang="ts">
/**
 * 本人のメッセージ本文を編集するダイアログ（CMP-261105-1507）。
 * 保存は呼び出し側が `PATCH /chat/messages/{id}` で行う。
 */
const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  /** 編集前の本文 */
  initialBody: string
  /** 保存中か */
  saving?: boolean
}>()

const emit = defineEmits<{
  save: [body: string]
}>()

const body = ref('')

watch(
  () => [visible.value, props.initialBody] as const,
  ([open, initial]) => {
    if (open) body.value = initial
  },
  { immediate: true },
)

const canSave = computed(() => body.value.trim().length > 0 && body.value !== props.initialBody)

function onSave() {
  if (!canSave.value) return
  emit('save', body.value.trim())
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('chat.message.editHeader')"
    modal
    class="w-full max-w-lg"
  >
    <Textarea
      v-model="body"
      class="w-full"
      rows="4"
      auto-resize
      data-testid="chat-message-edit-body"
    />
    <template #footer>
      <Button :label="$t('common.cancel')" text @click="visible = false" />
      <Button
        :label="$t('common.save')"
        :disabled="!canSave"
        :loading="saving"
        data-testid="chat-message-edit-save"
        @click="onSave"
      />
    </template>
  </Dialog>
</template>
