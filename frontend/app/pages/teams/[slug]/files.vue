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
    <template v-if="!pending && team && team.numericId != null">
      <!--
        scope-id には**数値 ID の string 表現**を渡す（slug では 404 になる）。
        `TeamResponse.id` は slug 移行後 URL 識別子（= slug）を返すため、ここで `id` を渡すと
        `GET /api/v1/files/folders?scope_type=TEAM&scope_id=fc-u-18` が
        404 FILE_SHARING_001 になり、フォルダが 1 件も表示されない（「ファイルがありません」）。
        内部 BIGINT が要るこの用途では `numericId` が正しい（types/team.ts の TeamResponse 参照）。
      -->
      <FileBrowser scope-type="TEAM" :scope-id="String(team.numericId)" />
    </template>
    <div v-else-if="pending" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
  </div>
</template>
