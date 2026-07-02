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
</script>

<template>
  <div class="mt-4">
    <ScopeDashboard
      :key="teamSlug"
      scope-type="team"
      :scope-id="teamSlug"
      :scope-name="displayName"
      :scope-template="team?.location?.template"
      :viewer-role="effectiveViewerRole"
      :is-admin-or-deputy="adminLens && isAdminOrDeputy"
      :visibility-map="widgetVisibilitySettings"
    />
  </div>
</template>
