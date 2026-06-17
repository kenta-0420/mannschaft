<script setup lang="ts">
/**
 * F08.9: チーム支払い管理ページ。
 * チーム ADMIN のみアクセス可（BE: checkAdminOrAbove）。
 */
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = String(route.params.slug)

const { isAdmin, loadPermissions } = useRoleAccess('team', teamId)

const loading = ref(true)
const permissionDenied = ref(false)

onMounted(async () => {
  try {
    await loadPermissions()
    if (!isAdmin.value) {
      permissionDenied.value = true
    }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <PageLoading v-if="loading" />

  <!-- 権限不足 -->
  <div v-else-if="permissionDenied" class="flex flex-col items-center justify-center py-16">
    <i class="pi pi-lock mb-4 text-4xl text-surface-400" />
    <p class="text-surface-500">{{ t('payment.admin.permissionDenied') }}</p>
    <BackButton class="mt-4" />
  </div>

  <!-- メインコンテンツ -->
  <div v-else>
    <div class="mb-4"><PageHeader :title="$t('payment.admin.pageTitle')" /></div>
    <PaymentAdminPanel scope-type="team" :scope-id="teamId" />
  </div>
</template>
