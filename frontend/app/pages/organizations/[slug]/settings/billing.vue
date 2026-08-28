<script setup lang="ts">
/**
 * F20.1 U-4: 組織課金管理タブ。
 * 閲覧はメンバー以上、操作（プラン変更・解約）は ADMIN のみ（BE 認可と一致）。
 */
definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const orgSlug = String(route.params.slug)
const { t } = useI18n()
const { isAdmin, loadPermissions } = useRoleAccess('organization', orgSlug)
const showHelp = ref(false)

onMounted(loadPermissions)
</script>

<template>
  <div class="mx-auto max-w-3xl p-4">
    <PageHeader :title="t('billing.manage.orgTitle')" help @help="showHelp = true" />
    <BillingHelpDialog v-model:visible="showHelp" variant="manage" />

    <BillingManagePanel scope-kind="ORG" :scope-id="orgSlug" :can-manage="isAdmin" />

    <div class="mt-6">
      <BetaPerkSection scope-kind="ORG" :scope-id="orgSlug" />
    </div>
  </div>
</template>
