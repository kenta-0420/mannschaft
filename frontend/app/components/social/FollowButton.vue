<script setup lang="ts">
import type { FollowTargetType } from '~/types/social-profile'

const props = defineProps<{
  followedType: FollowTargetType
  followedId: number
  isFollowing: boolean
}>()

const emit = defineEmits<{
  toggle: [isFollowing: boolean]
}>()

const socialApi = useSocialProfileApi()
const notification = useNotification()
const loading = ref(false)

async function toggleFollow() {
  loading.value = true
  try {
    if (props.isFollowing) {
      await socialApi.unfollow(props.followedType, props.followedId)
      emit('toggle', false)
    } else {
      await socialApi.follow(props.followedType, props.followedId)
      emit('toggle', true)
    }
  } catch {
    notification.error('操作に失敗しました')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Button
    :label="isFollowing ? 'フォロー中' : 'フォロー'"
    :icon="isFollowing ? 'pi pi-check' : 'pi pi-user-plus'"
    :severity="isFollowing ? 'secondary' : 'primary'"
    :outlined="isFollowing"
    :loading="loading"
    size="small"
    @click="toggleFollow"
  />
</template>
