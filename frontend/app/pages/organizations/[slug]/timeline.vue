<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdmin, isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

const feedRef = ref<{ refresh: () => void } | null>(null)
const loading = ref(true)

function onPosted() {
  feedRef.value?.refresh()
}

// 投稿カード本体クリックは返信アコーディオン開閉に統一済み（詳細遷移は各カードの時刻パーマリンク経由）。
// このため clickPost の購読は不要になり撤去した。

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
    <div class="mb-4">
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
      />
    </div>
  </div>
</template>
