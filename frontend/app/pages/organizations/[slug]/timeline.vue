<script setup lang="ts">
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))
const { org, isAdmin, isAdminOrDeputy } = useOrgShellContext()
// OrgDetail.idは公開slug。現在のURLと一致したshellの内部IDだけを使う。
const numericScopeId = computed(() => {
  const id = org.value?.numericId
  return org.value?.id === orgSlug.value && id != null && Number.isSafeInteger(id) && id > 0
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
    <div class="mb-4">
      <PageHeader title="タイムライン" />
    </div>

    <div class="mx-auto max-w-2xl">
      <TimelinePostForm
        :key="`${orgSlug}:${numericScopeId}`"
        scope-type="ORGANIZATION"
        :scope-id="numericScopeId"
        :scope-slug="orgSlug"
        class="mb-4"
        @posted="onPosted"
      />

      <TimelineFeed
        ref="feedRef"
        :key="`${orgSlug}:${numericScopeId}`"
        scope-type="ORGANIZATION"
        :scope-id="numericScopeId"
        :can-pin="isAdminOrDeputy"
        :can-delete-others="isAdmin"
      />
    </div>
  </div>
</template>
