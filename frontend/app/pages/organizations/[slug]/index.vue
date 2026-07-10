<script setup lang="ts">
/**
 * 組織詳細「ダッシュボード」タブ（永続シェル配下の子ルート）。
 *
 * ヘッダ・タブ・サイドバーは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 * 本ページは ScopeDashboard 本体のみを描画し、権限・可視性はシェルコンテキストから inject する。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const {
  org,
  displayName,
  effectiveViewerRole,
  adminLens,
  isAdminOrDeputy,
  widgetVisibilitySettings,
} = useOrgShellContext()
</script>

<template>
  <div class="mt-4">
    <ScopeDashboard
      :key="orgSlug"
      scope-type="organization"
      :scope-id="orgSlug"
      :scope-numeric-id="org?.id"
      :scope-name="displayName"
      :scope-template="org?.hierarchy?.orgType"
      :viewer-role="effectiveViewerRole"
      :is-admin-or-deputy="adminLens && isAdminOrDeputy"
      :visibility-map="widgetVisibilitySettings"
    />
  </div>
</template>
