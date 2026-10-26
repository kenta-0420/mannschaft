<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const orgSlug = String(route.params.slug)
const { isMember, loadPermissions } = useRoleAccess('organization', orgSlug)

const showGuide = ref(false)
onMounted(loadPermissions)
</script>

<template>
  <div>
    <PageHeader :title="$t('activity.pageTitle')" help @help="showGuide = true" />

    <ActivityRecordList scope-type="ORGANIZATION" :scope-id="orgSlug" :is-member="isMember" />

    <ActivityGuideModal v-model:visible="showGuide" />
  </div>
</template>
