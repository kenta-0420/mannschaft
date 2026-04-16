<script setup lang="ts">
/**
 * F01.5 フレンド関係公開設定トグル。
 *
 * ADMIN 権限のみ操作可能（設計書 §3）。
 * MANAGE_FRIEND_TEAMS を保持する DEPUTY_ADMIN でも不可。
 *
 * Props:
 *   teamFriendId — フレンド関係 ID
 *   teamId       — 自チーム ID
 *   isPublic     — 現在の公開状態
 *   disabled     — スイッチを操作不能にするか（権限不足時など）
 *
 * Emits:
 *   toggled — 切替成功時（新しい isPublic）
 */
const props = defineProps<{
  teamFriendId: number
  teamId: number
  isPublic: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  toggled: [isPublic: boolean]
}>()

const { t } = useI18n()
const { setVisibility } = useFriendTeamsApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

// 内部状態: props.isPublic に追従しつつ楽観更新する
const localPublic = ref(props.isPublic)
const submitting = ref(false)

watch(() => props.isPublic, (next) => {
  localPublic.value = next
})

async function onChange(next: boolean) {
  // 楽観的に UI を変更し、失敗時にロールバック
  const previous = !next
  submitting.value = true
  try {
    await setVisibility(props.teamId, props.teamFriendId, { isPublic: next })
    notification.success(t('friends.messages.visibility_changed'))
    emit('toggled', next)
  }
  catch (error) {
    localPublic.value = previous
    handleApiError(error)
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="flex items-center gap-2">
    <ToggleSwitch
      v-model="localPublic"
      :disabled="disabled || submitting"
      @update:model-value="onChange"
    />
    <span class="text-sm text-surface-500">
      {{ localPublic ? t('friends.list.visibility_public') : t('friends.list.visibility_private') }}
    </span>
  </div>
</template>
