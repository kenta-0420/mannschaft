<script setup lang="ts">
/**
 * チーム詳細「メンバー」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 *
 * # ネストルートとの共存
 *  `pages/teams/[slug]/members/[userId]/match-analytics.vue` が存在するため、本 `members.vue`
 *  は同時にそのネスト親レイアウトになる（Nuxt の同名ファイル+ディレクトリ規則）。
 *  そのため深いルート（/members/{userId}/…）では MemberTable ではなく <NuxtPage/> を
 *  描画してネスト子（match-analytics 等）へ委譲する。ちょうど `/members` のときだけ
 *  メンバー一覧を表示する。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const { isAdminOrDeputy } = useTeamShellContext()

/** ちょうど `/teams/{slug}/members`（ネスト子なし）か。 */
const isMembersRoot = computed<boolean>(() => {
  const last = route.path.replace(/\/+$/, '').split('/').pop() ?? ''
  return last === 'members'
})
</script>

<template>
  <div>
    <div v-if="isMembersRoot" class="mt-4">
      <MemberTable
        scope-type="team"
        :scope-id="teamSlug"
        :can-change-role="isAdminOrDeputy"
        :can-remove="isAdminOrDeputy"
      />
    </div>
    <!-- ネスト子（/members/{userId}/match-analytics 等）へ委譲 -->
    <NuxtPage v-else />
  </div>
</template>
