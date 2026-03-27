<script setup lang="ts">
import type { ChatChannelType } from '~/types/chat'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  teamId?: number
  organizationId?: number
}>()

const emit = defineEmits<{
  created: []
}>()

const { createChannel } = useChatApi()
const { showSuccess, showError } = useNotification()

const name = ref('')
const description = ref('')
const isPrivate = ref(false)
const submitting = ref(false)

const channelType = computed<ChatChannelType>(() => {
  if (props.teamId) return 'TEAM'
  if (props.organizationId) return 'ORGANIZATION'
  return 'CROSS_TEAM'
})

async function onSubmit() {
  if (!name.value.trim() || submitting.value) return
  submitting.value = true
  try {
    await createChannel({
      channelType: channelType.value,
      teamId: props.teamId,
      organizationId: props.organizationId,
      name: name.value.trim(),
      description: description.value.trim() || undefined,
      isPrivate: isPrivate.value,
    })
    showSuccess('チャンネルを作成しました')
    visible.value = false
    name.value = ''
    description.value = ''
    isPrivate.value = false
    emit('created')
  } catch {
    showError('チャンネルの作成に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="チャンネル作成" modal class="w-full max-w-md">
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">チャンネル名</label>
        <InputText v-model="name" class="w-full" placeholder="例: general" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">説明（任意）</label>
        <InputText v-model="description" class="w-full" placeholder="チャンネルの説明" />
      </div>
      <div class="flex items-center gap-2">
        <Checkbox v-model="isPrivate" :binary="true" input-id="private" />
        <label for="private" class="text-sm">プライベートチャンネル（招待制）</label>
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button label="作成" :loading="submitting" :disabled="!name.trim()" @click="onSubmit" />
    </template>
  </Dialog>
</template>
