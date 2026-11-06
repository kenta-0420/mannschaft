<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
useHead({ title: () => t('dashboard.attendance_results.detailTitle') })
</script>

<template>
  <AttendanceResultDetail
    scope-type="organization"
    :scope-id="String(route.params.slug)"
    :schedule-id="String(route.params.scheduleId)"
  />
</template>
