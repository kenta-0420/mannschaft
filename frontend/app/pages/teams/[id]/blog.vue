<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = String(route.params.id)
// メンバー判定はロールシステムに委譲する（全メンバー取得→線形探索のアンチパターンを排除）
const { isMember, loadPermissions } = useRoleAccess('team', teamId)

onMounted(async () => {
  await loadPermissions()
})
</script>

<template>
  <div>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader title="ブログ・お知らせ" />
    </div>
    <BlogPostList scope-type="TEAM" :scope-id="teamId" :can-create="isMember" />
  </div>
</template>
