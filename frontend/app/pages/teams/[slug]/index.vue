<script setup lang="ts">
/**
 * チーム詳細「ダッシュボード」タブ（永続シェル配下の子ルート）。
 *
 * ヘッダ・タブ・サイドバーは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 本ページは ScopeDashboard 本体のみを描画し、権限・可視性はシェルコンテキストから inject する。
 */
import { useTeamShellContext } from '~/composables/useTeamShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const {
  team,
  displayName,
  effectiveViewerRole,
  adminLens,
  isAdminOrDeputy,
  widgetVisibilitySettings,
} = useTeamShellContext()

// F09.19.10: Spotlight 掲載面用の数値スコープ ID。team.id は URL 識別子（slug）で数値化できないため
// （旧実装は team.id を誤って数値扱いしていた）、BE が内部連携専用に公開する numericId を文字列化して渡す。
const teamScopeNumericId = computed(() =>
  team.value?.numericId != null ? String(team.value.numericId) : undefined,
)
</script>

<template>
  <div class="mt-4">
    <ScopeDashboard
      :key="teamSlug"
      scope-type="team"
      :scope-id="teamSlug"
      :scope-numeric-id="teamScopeNumericId"
      :scope-name="displayName"
      :scope-template="team?.location?.template"
      :viewer-role="effectiveViewerRole"
      :is-admin-or-deputy="adminLens && isAdminOrDeputy"
      :visibility-map="widgetVisibilitySettings"
    />
  </div>
</template>
