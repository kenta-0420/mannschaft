<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  decisionType: 'APPROVED' | 'REJECTED'
  submitting: boolean
}>()

const emit = defineEmits<{
  submit: [comment: string]
}>()

const decisionComment = ref('')

watch(visible, (v) => {
  if (v) decisionComment.value = ''
})

function onSubmit() {
  emit('submit', decisionComment.value.trim())
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="props.decisionType === 'APPROVED' ? '承認' : '却下'"
    :style="{ width: '400px' }"
    modal
  >
    <div class="flex flex-col gap-4">
      <p>
        {{
          props.decisionType === 'APPROVED' ? 'この申請を承認しますか？' : 'この申請を却下しますか？'
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
      <Button label="キャンセル" text @click="visible = false" />
      <Button
        :label="props.decisionType === 'APPROVED' ? '承認' : '却下'"
        :severity="props.decisionType === 'APPROVED' ? 'success' : 'danger'"
        :loading="submitting"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
