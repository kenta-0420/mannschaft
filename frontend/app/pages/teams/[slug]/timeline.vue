<script setup lang="ts">
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const { team, isAdmin, isAdminOrDeputy } = useTeamShellContext()
// 切替前のshellを新しいURLの投稿先として使わず、取得済みの内部IDだけを渡す。
const numericScopeId = computed(() => {
  const id = team.value?.numericId
  return team.value?.slug === teamSlug.value && id != null && Number.isSafeInteger(id) && id > 0
    ? id
    : null
})

const feedRef = ref<{ refresh: () => void } | null>(null)

function onPosted() {
  feedRef.value?.refresh()
}

// 投稿カード本体クリックは返信アコーディオン開閉に統一済み（詳細遷移は各カードの時刻パーマリンク経由）。
// このため clickPost の購読は不要になり撤去した。
</script>

<template>
  <PageLoading v-if="!numericScopeId" />
  <div v-else>
    <div class="mb-4 flex items-center gap-3">
      <PageHeader title="タイムライン" />
    </div>

    <div class="mx-auto max-w-2xl">
      <TimelinePostForm
        :key="`${teamSlug}:${numericScopeId}`"
        scope-type="TEAM"
        :scope-id="numericScopeId"
        :scope-slug="teamSlug"
        class="mb-4"
        @posted="onPosted"
      />

      <TimelineFeed
        ref="feedRef"
        :key="`${teamSlug}:${numericScopeId}`"
        scope-type="TEAM"
        :scope-id="numericScopeId"
        :can-pin="isAdminOrDeputy"
        :can-delete-others="isAdmin"
      />
    </div>
  </div>
</template>
