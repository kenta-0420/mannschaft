<script setup lang="ts">
const { t } = useI18n()
const orgStore = useOrganizationStore()
</script>

<template>
  <DashboardWidgetCard :title="t('dashboard.widgets.myOrganizations.title')" icon="pi pi-building" to="/organizations">
    <template v-if="orgStore.myOrganizations.length > 0">
      <div class="flex flex-wrap gap-2">
        <NuxtLink
          v-for="org in orgStore.myOrganizations.slice(0, 20)"
          :key="org.id"
          :to="org.slug ? `/organizations/${org.slug}` : undefined"
          class="flex items-center gap-2 rounded-lg border border-surface-400 bg-surface-50 px-3 py-2 text-sm transition-shadow hover:shadow-md dark:border-surface-600 dark:bg-surface-700"
        >
          <i class="pi pi-building text-xs text-primary" />
          <div class="min-w-0 flex-1">
            <p class="truncate font-medium">{{ org.nickname1 || org.name }}</p>
            <p class="text-xs text-surface-500">{{ org.orgType === 'NONPROFIT' ? t('dashboard.widgets.myOrganizations.nonprofit') : t('dashboard.widgets.myOrganizations.forProfit') }}</p>
          </div>
          <RoleBadge :role="org.role" />
        </NuxtLink>
      </div>
      <div class="mt-3 flex justify-end">
        <NuxtLink to="/organizations" class="text-sm text-primary hover:underline">{{ t('dashboard.widgets.common.viewAll') }}</NuxtLink>
      </div>
    </template>
    <DashboardEmptyState v-else icon="pi pi-building" :message="t('dashboard.widgets.myOrganizations.empty')" />
  </DashboardWidgetCard>
</template>
