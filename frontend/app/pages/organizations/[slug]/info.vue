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

const { org, isAdmin, ancestors } = useOrgShellContext()
</script>

<template>
  <OrgInfoTab v-if="org" :org="org" :is-admin="isAdmin" :ancestors="ancestors" />
</template>
