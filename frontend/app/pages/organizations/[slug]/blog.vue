<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgId = String(route.params.slug)
// メンバー判定はロールシステムに委譲する（全メンバー取得→線形探索のアンチパターンを排除）
const { isMember, loadPermissions } = useRoleAccess('organization', orgId)

onMounted(async () => {
  await loadPermissions()
})
</script>

<template>
  <div>
    <div class="mb-4">
      <PageHeader title="ブログ・お知らせ" />
    </div>
    <BlogPostList scope-type="ORGANIZATION" :scope-id="orgId" :can-create="isMember" />
  </div>
</template>
