<script setup lang="ts">
/**
 * 組織詳細「所属チーム」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 *
 * # 命名について
 *  既存の `organizations/[slug]/teams/search.vue`（組織内チーム検索）と物理衝突するため、
 *  タブのルートセグメントは `teams` ではなく `member-teams` にしている。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const { orgTeams, showTeamSearchLink } = useOrgShellContext()
</script>

<template>
  <div>
    <div
      v-if="showTeamSearchLink"
      class="mt-4 flex justify-end"
    >
      <NuxtLink
        :to="`/organizations/${orgSlug}/teams/search`"
        :aria-label="$t('organizationTeamSearch.title')"
        class="inline-flex items-center gap-2 rounded-md border border-primary-300 bg-primary-50 px-3 py-2 text-sm font-medium text-primary-700 transition-colors hover:bg-primary-100 focus:outline-none focus:ring-2 focus:ring-primary-400"
      >
        <i class="pi pi-search" aria-hidden="true" />
        {{ $t('organizationTeamSearch.title') }}
      </NuxtLink>
    </div>
    <OrgTeamGrid :teams="orgTeams" />
  </div>
</template>
