<script setup lang="ts">
definePageMeta({ layout: 'organization', middleware: 'auth' })
const route = useRoute()
const orgSlug = String(route.params.slug)
const { getOrganization } = useOrganizationApi()

/**
 * slug → 数値 organizationId を解決する。
 * SharedFolderController の scope_id は数値文字列を要求するため、slug をそのまま渡すと
 * parseScopeId で NumberFormatException が発生し 404 になる。
 */
const { data: org, pending } = await useAsyncData(`org-files-${orgSlug}`, () =>
  getOrganization(orgSlug).then((r) => r.data),
)
</script>

<template>
  <div>
    <PageHeader title="ファイル共有" />
    <template v-if="!pending && org">
      <!-- scope-id に数値 ID（string 表現）を渡す。slug ではなく数値 ID が必要 -->
      <FileBrowser scope-type="ORGANIZATION" :scope-id="org.id" />
    </template>
    <div v-else-if="pending" class="flex justify-center py-8">
      <LoadingBounce />
    </div>
  </div>
</template>
