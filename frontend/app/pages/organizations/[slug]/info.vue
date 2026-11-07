<script setup lang="ts">
/**
 * 組織詳細「基本情報」タブ（永続シェル配下の子ルート）。
 * ヘッダ・タブは親 `pages/organizations/[slug].vue`（ScopePageShell）が常駐描画する。
 */
import { useOrgShellContext } from '~/composables/useOrgShellContext'

definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

const { org, isAdmin, ancestors, refresh } = useOrgShellContext()
</script>

<template>
  <div>
    <!-- out-in 遷移は単一要素を必要とする。Fragment を返す OrgInfoTab と org 未取得時も包む。 -->
    <OrgInfoTab
      v-if="org"
      :org="org"
      :is-admin="isAdmin"
      :ancestors="ancestors"
      @updated="refresh"
    />
  </div>
</template>
