<script setup lang="ts">
/**
 * F01.5 フォロー追加ダイアログ。
 *
 * Phase 1 の仕様:
 * - 対象チーム ID を直接入力（Phase 2 以降でチーム検索 UI を追加予定）
 * - 挨拶コメント（任意、最大 300 文字）
 * - 実行は {@link TeamFriendFollowButton} に委譲し、NOWAIT 自動リトライの恩恵を受ける。
 *
 * Props:
 *   teamId       — 自チーム ID
 *   modelValue   — 表示状態（v-model）
 *
 * Emits:
 *   update:modelValue — 表示状態の変更
 *   success           — フォロー成功時（一覧の再取得要求）
 */
import type { FollowTeamResponse } from '~/types/friends'

const props = defineProps<{
  teamId: number
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'success': [response: FollowTeamResponse]
}>()

const { t } = useI18n()

const COMMENT_MAX = 300

const targetTeamId = ref<number | null>(null)
const comment = ref<string>('')

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

const canSubmit = computed(
  () => typeof targetTeamId.value === 'number'
    && Number.isFinite(targetTeamId.value)
    && targetTeamId.value > 0
    && targetTeamId.value !== props.teamId
    && (comment.value?.length ?? 0) <= COMMENT_MAX,
)

const commentRemaining = computed(() => COMMENT_MAX - (comment.value?.length ?? 0))

function resetForm() {
  targetTeamId.value = null
  comment.value = ''
}

// 開閉に合わせてフォームをリセット（閉じた瞬間にクリア）
watch(visible, (next) => {
  if (!next) resetForm()
})

function onFollowed(response: FollowTeamResponse) {
  emit('success', response)
  visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('friends.actions.follow')"
    modal
    class="w-full max-w-md"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium" for="friend-follow-target">
          {{ t('friends.list.target_team_id') }}
        </label>
        <InputNumber
          id="friend-follow-target"
          v-model="targetTeamId"
          :min="1"
          :use-grouping="false"
          class="w-full"
        />
        <p
          v-if="targetTeamId !== null && targetTeamId === teamId"
          class="mt-1 text-sm text-red-500"
        >
          {{ t('friends.errors.self_follow') }}
        </p>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium" for="friend-follow-comment">
          {{ t('friends.list.comment_label') }}
        </label>
        <Textarea
          id="friend-follow-comment"
          v-model="comment"
          :maxlength="COMMENT_MAX"
          rows="3"
          class="w-full"
        />
        <p class="mt-1 text-right text-xs text-surface-500">
          {{ commentRemaining }}
        </p>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('friend_feed.forward.cancel')"
        text
        @click="visible = false"
      />
      <TeamFriendFollowButton
        :team-id="teamId"
        :target-team-id="targetTeamId ?? 0"
        :comment="comment || undefined"
        :disabled="!canSubmit"
        @followed="onFollowed"
      />
    </template>
  </Dialog>
</template>
