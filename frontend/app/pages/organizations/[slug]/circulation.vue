<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgSlug = String(route.params.slug)
const { isAdminOrDeputy, loadPermissions } = useRoleAccess('organization', orgSlug)

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
    <div class="mb-4">
      <PageHeader :title="t('confirmable.guide.circulation.title')" />
    </div>
    <ConfirmableCirculationGuide
      v-if="isAdminOrDeputy"
      current-feature="circulation"
      :target-path="`/organizations/${orgSlug}/settings/confirmable-notifications`"
    />
    <CirculationList scope-type="ORGANIZATION" :scope-id="orgSlug" :can-manage="isAdminOrDeputy" />
  </div>
</template>
