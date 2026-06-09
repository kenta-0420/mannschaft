<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgId = String(route.params.id)
const authStore = useAuthStore()
const { getMembers } = useOrganizationApi()

const isMember = ref(false)

onMounted(async () => {
  const currentUserId = authStore.currentUser?.id
  if (!currentUserId) {
    isMember.value = false
    return
  }
  try {
    const res = await getMembers(orgId, { size: 500 })
    isMember.value = res.data.some((m) => m.userId === currentUserId)
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
    <BlogPostList scope-type="ORGANIZATION" :scope-id="orgId" :can-create="isMember" />
  </div>
</template>
