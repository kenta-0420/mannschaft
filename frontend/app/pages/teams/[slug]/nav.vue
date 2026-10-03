<script setup lang="ts">
/**
 * チーム詳細「フレンドチーム」タブ（永続シェル配下の子ルート・所属者のみ）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 管理者にはフォルダ/フィード/転送履歴へのショートカットを表示する。
 * 直リンク防御は roleName ガード（未所属＝roleName falsy では本文非表示）で行う。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { isAdmin, isAdminOrDeputy, roleName } = useTeamShellContext()
</script>

<template>
  <div class="mt-4">
    <template v-if="roleName">
      <!-- 管理者向けショートカット -->
      <div v-if="isAdmin" class="mb-4 grid grid-cols-2 gap-3">
        <NuxtLink :to="`/teams/${teamSlug}/friend-folders`">
          <Button :label="$t('folders.title')" icon="pi pi-folder-open" class="w-full" outlined />
        </NuxtLink>
        <NuxtLink :to="`/teams/${teamSlug}/friend-feed`">
          <Button :label="$t('friend_feed.title')" icon="pi pi-inbox" class="w-full" outlined />
        </NuxtLink>
        <NuxtLink :to="`/teams/${teamSlug}/friend-forward-exports`">
          <Button :label="$t('forward_exports.title')" icon="pi pi-history" class="w-full" outlined />
        </NuxtLink>
      </div>
      <TeamFriendList
        :team-id="teamSlug"
        :can-edit="isAdminOrDeputy"
        :can-toggle-visibility="isAdmin"
      />
    </template>
  </div>
</template>
