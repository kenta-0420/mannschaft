<script setup lang="ts">
import type { FriendRequestTarget } from '~/types/friends'
const props = defineProps<{ targetTeam: FriendRequestTarget; profileKind: 'public' | 'authorized' }>()
const { t } = useI18n()
const auth = useAuthStore()
const open = ref(false)
async function start() {
  if (!import.meta.client) return
  auth.loadFromStorage()
  if (!auth.isAuthenticated) {
    // 既知のプロフィールパスだけを構築し、任意のfullPathや外部URLを転送しない。
    const slug = encodeURIComponent(props.targetTeam.teamSlug)
    const redirect = props.profileKind === 'public' ? '/public/teams/' + slug : '/teams/' + slug + '/info'
    await navigateTo({ path: '/login', query: { redirect } })
    return
  }
  open.value = true
}
</script>
<template>
  <div class="mt-3 min-w-0">
    <Button :label="t('friends.request.title')" icon="pi pi-user-plus" class="min-h-11 !h-auto !whitespace-normal" data-testid="friend-request-entry" @click="start" />
    <FriendFollowDialog v-if="open" v-model="open" team-id="" :target-team="targetTeam" />
  </div>
</template>
