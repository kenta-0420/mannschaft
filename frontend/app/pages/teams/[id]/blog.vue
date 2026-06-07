<script setup lang="ts">
import { useTeamMembers } from '~/composables/team/useTeamMembers'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = String(route.params.id)
const authStore = useAuthStore()
const { getMembers } = useTeamMembers()

const isMember = ref(false)

onMounted(async () => {
  const currentUserId = authStore.currentUser?.id
  if (!currentUserId) {
    isMember.value = false
    return
  }
  try {
    const res = await getMembers(teamId, { size: 500 })
    isMember.value = res.data.some((m: { userId: number }) => m.userId === currentUserId)
  } catch {
    isMember.value = false
  }
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="ブログ・お知らせ" />
    </div>
    <BlogPostList scope-type="TEAM" :scope-id="teamId" :can-create="isMember" />
  </div>
</template>
