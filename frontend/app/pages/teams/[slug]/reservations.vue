<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamSlug = String(route.params.slug)
</script>

<template>
  <div>
    <PageHeader :title="t('reservation.page.team_title')" class="mb-4" />
    <TeamReservationsPanel :team-id="teamSlug" />
  </div>
</template>
