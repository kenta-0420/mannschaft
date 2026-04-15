<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('team', teamId)

const loading = ref(true)

onMounted(async () => {
  try {
    await loadPermissions()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <PageLoading v-if="loading" />
  <div v-else>
    <!-- デスクトップ: サイドパネルレイアウト -->
    <div class="flex gap-4">
      <div class="min-w-0 flex-1">
        <EquipmentList scope-type="team" :scope-id="teamId" :can-manage="isAdminOrDeputy" />
      </div>
      <!-- デスクトップのみサイドパネル表示 -->
      <aside class="hidden w-80 shrink-0 lg:block">
        <EquipmentTrending :team-id="teamId" />
      </aside>
    </div>
    <!-- モバイル・タブレット: 下部表示 -->
    <div class="mt-4 lg:hidden">
      <EquipmentTrending :team-id="teamId" />
    </div>
  </div>
</template>
