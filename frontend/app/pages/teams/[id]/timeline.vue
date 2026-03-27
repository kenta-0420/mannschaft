<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const router = useRouter()
const teamId = Number(route.params.id)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const feedRef = ref<{ refresh: () => void } | null>(null)

function onPosted() {
  feedRef.value?.refresh()
}

function onClickPost(postId: number) {
  router.push(`/timeline/${postId}`)
}

onMounted(() => loadPermissions())
</script>

<template>
  <div>
    <div class="mb-4">
      <h1 class="text-2xl font-bold">タイムライン</h1>
    </div>

    <div class="mx-auto max-w-2xl">
      <TimelinePostForm
        scope-type="TEAM"
        :scope-id="teamId"
        class="mb-4"
        @posted="onPosted"
      />

      <TimelineFeed
        ref="feedRef"
        scope-type="TEAM"
        :scope-id="teamId"
        :can-pin="isAdminOrDeputy"
        :can-delete-others="isAdmin"
        @click-post="onClickPost"
      />
    </div>
  </div>
</template>
