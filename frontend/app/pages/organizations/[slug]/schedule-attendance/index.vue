<script setup lang="ts">
definePageMeta({ middleware: 'auth' })
const route = useRoute()
const { t } = useI18n()
useHead({ title: () => t('dashboard.attendance_results.listTitle') })
</script>

<template>
  <AttendanceResultsList scope-type="organization" :scope-id="String(route.params.slug)" />
</template>
