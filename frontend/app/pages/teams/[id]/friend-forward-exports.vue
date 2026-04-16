<script setup lang="ts">
/**
 * F01.5 逆転送履歴ページ。
 *
 * 自チーム投稿が他フレンドチームへ転送された履歴を閲覧する（透明性確保用）。
 * 非公開フレンドの名前は「匿名チーム」に匿名化される。
 *
 * 権限: ADMIN or MANAGE_FRIEND_TEAMS 保持 DEPUTY_ADMIN
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = Number(route.params.id)

const { isAdmin, can, loadPermissions } = useRoleAccess('team', teamId)

const loading = ref(true)
const permissionDenied = ref(false)

/** 権限チェック */
onMounted(async () => {
  try {
    await loadPermissions()
    if (!isAdmin.value && !can('MANAGE_FRIEND_TEAMS')) {
      permissionDenied.value = true
    }
  }
  finally {
    loading.value = false
  }
})
</script>

<template>
  <PageLoading v-if="loading" />

  <!-- 権限不足 -->
  <div v-else-if="permissionDenied" class="flex flex-col items-center justify-center py-16">
    <i class="pi pi-lock mb-4 text-4xl text-surface-400" />
    <p class="text-surface-500">{{ t('forward_exports.permission_denied') }}</p>
    <BackButton class="mt-4" />
  </div>

  <!-- メインコンテンツ -->
  <div v-else>
    <div class="mb-4 flex items-center gap-3">
      <BackButton />
      <PageHeader :title="t('forward_exports.title')">
        <span class="text-sm text-surface-400">{{ t('forward_exports.subtitle') }}</span>
      </PageHeader>
    </div>

    <div class="mx-auto max-w-3xl">
      <SectionCard>
        <FriendsFriendForwardExportList :team-id="teamId" />
      </SectionCard>
    </div>
  </div>
</template>
