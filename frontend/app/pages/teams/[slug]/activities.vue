<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamSlug = String(route.params.slug)
const { isMember, loadPermissions } = useRoleAccess('team', teamSlug)

const showGuide = ref(false)
onMounted(loadPermissions)
</script>

<template>
  <div>
    <PageHeader :title="$t('activity.pageTitle')" help @help="showGuide = true" />

    <ActivityRecordList scope-type="TEAM" :scope-id="teamSlug" :is-member="isMember" />

    <ActivityGuideModal v-model:visible="showGuide" />
  </div>
</template>
