<script setup lang="ts">
const { state, submitComment, close } = useErrorReport()

const userComment = ref('')
const isDev = import.meta.dev

const handleSubmit = async () => {
  await submitComment(userComment.value)
  userComment.value = ''
}

const handleClose = () => {
  userComment.value = ''
  close()
}
</script>

<template>
  <Dialog
    :visible="state.visible && !isDev"
    modal
    header="エラーが発生しました"
    :style="{ width: '30rem' }"
    :breakpoints="{ '640px': '90vw' }"
    :closable="!state.submitting"
    @update:visible="handleClose"
  >
    <div v-if="!state.submitted">
      <p class="text-muted-color mb-4">
        予期しないエラーが発生しました。よろしければ状況を報告していただけると、改善に役立ちます。
      </p>

      <div class="flex flex-col gap-2">
        <label for="errorComment" class="font-medium">状況の説明（任意）</label>
        <Textarea
          id="errorComment"
          v-model="userComment"
          rows="4"
          placeholder="エラー発生時に行っていた操作を教えてください..."
          :disabled="state.submitting"
          class="w-full"
        />
      </div>
    </div>

    <div v-else class="flex flex-col items-center gap-4 py-4">
      <i class="pi pi-check-circle text-green-500" style="font-size: 3rem" />
      <p class="text-center font-medium">
        ご報告ありがとうございます。<br >開発チームが確認します。
      </p>
    </div>

    <template #footer>
      <template v-if="!state.submitted">
        <Button
          label="閉じる"
          text
          :disabled="state.submitting"
          @click="handleClose"
        />
        <Button
          label="送信する"
          icon="pi pi-send"
          :loading="state.submitting"
          @click="handleSubmit"
        />
      </template>
    </template>
  </Dialog>
</template>
