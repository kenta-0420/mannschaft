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

// F09.19.10: Spotlight 掲載面用の数値スコープ ID。org.id は URL 識別子（slug）で数値化できないため、
// BE が内部連携専用に公開する numericId（BIGINT）を文字列化して渡す（team 側の scopeNumericId と同型）。
const orgScopeNumericId = computed(() =>
  org.value?.numericId != null ? String(org.value.numericId) : undefined,
)
</script>

<template>
  <div class="mt-4">
    <ScopeDashboard
      :key="orgSlug"
      scope-type="organization"
      :scope-id="orgSlug"
      :scope-numeric-id="orgScopeNumericId"
      :scope-name="displayName"
      :scope-template="org?.hierarchy?.orgType"
      :viewer-role="effectiveViewerRole"
      :is-admin-or-deputy="adminLens && isAdminOrDeputy"
      :visibility-map="widgetVisibilitySettings"
    />
  </div>
</template>
