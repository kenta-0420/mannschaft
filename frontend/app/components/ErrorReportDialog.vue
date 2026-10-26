<script setup lang="ts">
const { state, submitComment, close, expand, minimize } = useErrorReport()
const { t } = useI18n()

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
  () => state.value.visible,
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
    <!--
      CMP-260920-1042: 自動展開したパネルが下部の操作ボタン（例: 「回覧作成」）を覆い、
      クリックを物理的に塞いでいた。対策として、
      1) エラー発生時は自動展開せず、まず小さいバッジのみを出す（利用者がクリックしたときだけ展開）
      2) 展開パネルも操作ボタンが集中しがちな画面右下（クイックメモ／各種FABの定位置）を避け、
         右上（ヘッダー直下）に配置する
      の二段構えで、操作要素を覆わないようにしている。エラー報告機能自体（自動送信・追加コメント）は維持。
    -->
    <Transition name="error-report-badge">
      <button
        v-if="state.visible && !state.expanded"
        type="button"
        class="fixed top-20 right-4 z-50 flex h-11 w-11 items-center justify-center rounded-full border border-surface-300 bg-surface-0 shadow-lg dark:border-surface-600 dark:bg-surface-800"
        :aria-label="t('error_report.widget.badge_aria_label')"
        :title="t('error_report.widget.badge_tooltip')"
        @click="expand"
      >
        <i class="pi pi-shield text-primary" />
        <span
          v-if="!state.submitted || state.submitting"
          class="absolute -right-0.5 -top-0.5 h-2.5 w-2.5 rounded-full bg-red-500"
        />
      </button>
    </Transition>

    <Transition name="error-report">
      <div
        v-if="state.visible && state.expanded"
        class="fixed top-20 right-4 z-50 w-80 rounded-xl border border-surface-300 bg-surface-0 shadow-xl dark:border-surface-600 dark:bg-surface-800"
      >
        <!-- Header -->
        <div
          class="flex items-center justify-between border-b border-surface-200 px-4 py-3 dark:border-surface-600"
        >
          <div class="flex items-center gap-2">
            <i class="pi pi-shield text-primary" />
            <span class="text-sm font-semibold">エラー報告</span>
          </div>
          <div class="flex items-center gap-0.5">
            <Button icon="pi pi-minus" text rounded size="small" @click="minimize" />
            <Button icon="pi pi-times" text rounded size="small" class="-mr-1" @click="close" />
          </div>
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
.error-report-badge-enter-active,
.error-report-badge-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.error-report-badge-enter-from,
.error-report-badge-leave-to {
  opacity: 0;
  transform: scale(0.8);
}

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
