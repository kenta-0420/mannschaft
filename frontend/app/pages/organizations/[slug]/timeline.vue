<script setup lang="ts">
import type { TimelineScopeType } from '~/types/timeline'
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
const feedReadable = ref(false)

// 読取成立は投稿権限の新設ではなく、既存フォームを見せる前提。
watch([orgSlug, numericScopeId], () => { feedReadable.value = false }, { flush: 'sync' })

function onReadAccess(allowed: boolean, scopeType: TimelineScopeType | undefined, scopeId: string | number | undefined) {
  if (scopeType === 'ORGANIZATION' && scopeId === numericScopeId.value) feedReadable.value = allowed
}

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
        v-if="feedReadable"
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
        @read-access="onReadAccess"
      />
    </div>
  </div>
</template>
