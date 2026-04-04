<script setup lang="ts">
const { state, submitComment, close } = useErrorReport()

const comment = ref('')
const commentSubmitting = ref(false)
const commentSent = ref(false)

async function sendComment() {
  if (commentSubmitting.value) return
  commentSubmitting.value = true
  try {
    await submitComment(comment.value.trim())
    commentSent.value = true
    setTimeout(() => close(), 2000)
  } finally {
    commentSubmitting.value = false
  }
}

watch(
  () => state.visible,
  (v) => {
    if (!v) {
      comment.value = ''
      commentSubmitting.value = false
      commentSent.value = false
    }
  },
)
</script>

<template>
  <Teleport to="body">
    <Transition name="error-report">
      <div
        v-if="state.visible"
        class="fixed bottom-4 right-4 z-50 w-80 rounded-xl border border-surface-200 bg-surface-0 shadow-xl dark:border-surface-700 dark:bg-surface-800"
      >
        <!-- Header -->
        <div
          class="flex items-center justify-between border-b border-surface-200 px-4 py-3 dark:border-surface-700"
        >
          <div class="flex items-center gap-2">
            <i class="pi pi-shield text-primary" />
            <span class="text-sm font-semibold">エラー報告</span>
          </div>
          <Button icon="pi pi-times" text rounded size="small" class="-mr-1" @click="close" />
        </div>

        <!-- Body -->
        <div class="p-4 space-y-3">
          <!-- Auto-report status -->
          <div class="flex items-center gap-2 text-sm">
            <template v-if="state.submitting && !state.submitted">
              <i class="pi pi-spin pi-spinner text-surface-400" />
              <span class="text-surface-500">開発者に送信中...</span>
            </template>
            <template v-else>
              <i class="pi pi-check-circle text-green-500" />
              <span class="text-surface-600 dark:text-surface-300"
                >開発者にエラーを報告しました</span
              >
            </template>
          </div>

          <!-- Error summary -->
          <div class="rounded-lg bg-surface-100 px-3 py-2 dark:bg-surface-700">
            <p class="truncate text-xs text-surface-500 dark:text-surface-400">
              {{ state.errorMessage }}
            </p>
            <p
              v-if="state.requestId"
              class="mt-0.5 text-xs text-surface-400 dark:text-surface-500 font-mono"
            >
              ID: {{ state.requestId }}
            </p>
          </div>

          <!-- Comment section -->
          <template v-if="!commentSent">
            <Textarea
              v-model="comment"
              placeholder="何をしていたか教えてください（任意）"
              :rows="3"
              class="w-full text-sm"
              :disabled="commentSubmitting"
            />
            <Button
              label="追加情報を送る"
              icon="pi pi-send"
              size="small"
              class="w-full"
              :loading="commentSubmitting"
              :disabled="commentSubmitting"
              @click="sendComment"
            />
          </template>
          <div
            v-else
            class="flex items-center gap-2 rounded-lg bg-green-50 px-3 py-2 text-sm text-green-700 dark:bg-green-900/20 dark:text-green-400"
          >
            <i class="pi pi-heart" />
            <span>ご協力ありがとうございます！</span>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.error-report-enter-active,
.error-report-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.error-report-enter-from,
.error-report-leave-to {
  opacity: 0;
  transform: translateY(0.5rem);
}
</style>
