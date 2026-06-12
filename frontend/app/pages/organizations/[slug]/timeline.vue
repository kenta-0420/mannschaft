<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
  layout: 'organization',
})

const route = useRoute()
const router = useRouter()
const orgSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

const feedRef = ref<{ refresh: () => void } | null>(null)
const loading = ref(true)

function onPosted() {
  feedRef.value?.refresh()
}

function onClickPost(postId: number) {
  router.push(`/timeline/${postId}`)
}

onMounted(async () => {
  try {
    await loadPermissions()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="タイムライン" />
    </div>

    <div class="mx-auto max-w-2xl">
      <TimelinePostForm
        scope-type="ORGANIZATION"
        :scope-id="orgSlug"
        class="mb-4"
        @posted="onPosted"
      />

      <TimelineFeed
        ref="feedRef"
        scope-type="ORGANIZATION"
        :scope-id="orgSlug"
        :can-pin="isAdminOrDeputy"
        :can-delete-others="isAdmin"
        @click-post="onClickPost"
      />
    </div>
  </div>
</template>
