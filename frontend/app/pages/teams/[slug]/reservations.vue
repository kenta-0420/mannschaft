<script setup lang="ts">
/**
 * チーム詳細「予約」タブ（永続シェル配下の子ルート）。
 *
 * ヘッダ・タブ・サイドバーは親 `pages/teams/[slug].vue`（ScopePageShell）が常駐描画する。
 * 旧実装は layout:'team' + 独自 PageHeader を持っていたが、永続シェル化に伴い撤去し、
 * TeamReservationsPanel 本体のみを描画する（二重ヘッダ回避）。
 * WidgetMyTeams / admin ページからの `/teams/{slug}/reservations` 直リンクにも対応する。
 */
definePageMeta({ layout: 'default', middleware: 'auth' })

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
</script>

<template>
  <div class="mt-4">
    <TeamReservationsPanel :team-id="teamSlug" />
  </div>
</template>
