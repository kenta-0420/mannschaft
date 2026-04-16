<script setup lang="ts">
/**
 * F01.5 転送モーダル。
 *
 * 管理者フィードの投稿を自チーム内タイムラインへ転送する。
 * Phase 1 では配信範囲は MEMBER 固定。
 * MEMBER_AND_SUPPORTER は Phase 3 で解禁予定（disabled + ツールチップ表示）。
 */
import type { ForwardTarget } from '~/types/friendForward'

const { t } = useI18n()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { forward } = useFriendForwardApi()

const props = defineProps<{
  modelValue: boolean
  teamId: number
  postId: number | null
  sourceTeamName: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'success': [forwardId: number]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const selectedTarget = ref<ForwardTarget>('MEMBER')
const comment = ref('')
const submitting = ref(false)

/** コメント文字数 */
const commentLength = computed(() => comment.value.length)
const commentMaxLength = 500

/** モーダルが閉じるときにフォームをリセットする */
watch(visible, (v) => {
  if (!v) {
    selectedTarget.value = 'MEMBER'
    comment.value = ''
    submitting.value = false
  }
})

async function onSubmit() {
  if (!props.postId) return
  submitting.value = true
  try {
    const result = await forward(props.teamId, props.postId, {
      target: selectedTarget.value,
      comment: comment.value.trim() || undefined,
    })
    notification.success(t('friend_feed.forward.success'))
    emit('success', result.forwardId)
    visible.value = false
  }
  catch (error) {
    handleApiError(error, 'friend-forward')
  }
  finally {
    submitting.value = false
  }
}

function onCancel() {
  visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="t('friend_feed.forward.modal_title')"
    :style="{ width: '480px' }"
    :closable="!submitting"
    :close-on-escape="!submitting"
  >
    <div class="flex flex-col gap-5">
      <!-- 転送元情報 -->
      <div class="flex items-center gap-2 text-sm text-surface-500">
        <i class="pi pi-share-alt" />
        <span>{{ sourceTeamName }}</span>
      </div>

      <!-- 配信範囲選択 -->
      <div class="flex flex-col gap-2">
        <label class="text-sm font-semibold">{{ t('friend_feed.forward.target_label') }}</label>

        <div class="flex flex-col gap-3">
          <!-- MEMBER（有効） -->
          <div class="flex items-center gap-2">
            <RadioButton
              v-model="selectedTarget"
              input-id="target-member"
              name="forwardTarget"
              value="MEMBER"
              :disabled="submitting"
            />
            <label for="target-member" class="text-sm cursor-pointer">
              {{ t('friend_feed.forward.target_member') }}
            </label>
          </div>

          <!-- MEMBER_AND_SUPPORTER（disabled, Phase 3） -->
          <div
            v-tooltip.right="t('friend_feed.forward.target_supporter_tooltip')"
            class="flex items-center gap-2 opacity-50"
          >
            <RadioButton
              v-model="selectedTarget"
              input-id="target-supporter"
              name="forwardTarget"
              value="MEMBER_AND_SUPPORTER"
              :disabled="true"
            />
            <label for="target-supporter" class="text-sm cursor-not-allowed">
              {{ t('friend_feed.forward.target_supporter') }}
            </label>
          </div>
        </div>
      </div>

      <!-- コメント入力 -->
      <div class="flex flex-col gap-2">
        <label for="forward-comment" class="text-sm font-semibold">
          {{ t('friend_feed.forward.comment_label') }}
        </label>
        <Textarea
          id="forward-comment"
          v-model="comment"
          :placeholder="t('friend_feed.forward.comment_placeholder')"
          :maxlength="commentMaxLength"
          rows="3"
          :disabled="submitting"
          auto-resize
        />
        <small class="text-right text-surface-400">
          {{ commentLength }} / {{ commentMaxLength }}
        </small>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          :label="t('friend_feed.forward.cancel')"
          severity="secondary"
          text
          :disabled="submitting"
          @click="onCancel"
        />
        <Button
          :label="t('friend_feed.forward.submit')"
          icon="pi pi-share-alt"
          :loading="submitting"
          :disabled="!postId"
          @click="onSubmit"
        />
      </div>
    </template>
  </Dialog>
</template>
