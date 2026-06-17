<script setup lang="ts">
/**
 * F08.9: 組織支払い管理ページ。
 * 組織 ADMIN のみアクセス可（BE: checkAdminOrAbove）。
 */
definePageMeta({ layout: 'organization', middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)

const { isAdmin, loadPermissions } = useRoleAccess('organization', orgSlug)

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
    <PageHeader :title="$t('payment.admin.pageTitle')" />
    <PaymentAdminPanel scope-type="organization" :scope-id="orgSlug" />
  </div>
</template>
