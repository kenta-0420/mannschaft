<script setup lang="ts">
definePageMeta({ layout: 'team', middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
const { getTeam } = useTeamApi()

/**
 * slug → 数値 teamId を解決する。
 * SharedFolderController の scope_id は数値文字列を要求するため、slug をそのまま渡すと
 * parseScopeId で NumberFormatException が発生し 404 になる。
 */
const { data: team, pending } = await useAsyncData(`team-files-${teamSlug}`, () =>
  getTeam(teamSlug).then((r) => r.data),
)
</script>

<template>
  <div>
    <div class="mb-4"><PageHeader title="ファイル共有" /></div>
    <template v-if="!pending && team">
      <!-- scope-id に数値 ID（string 表現）を渡す。slug ではなく数値 ID が必要 -->
      <FileBrowser scope-type="TEAM" :scope-id="team.id" />
    </template>
    <div v-else-if="pending" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
  </div>
</template>
