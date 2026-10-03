<script setup lang="ts">
definePageMeta({ middleware: 'auth', layout: 'default' })

const route = useRoute()
const slug = computed(() => String(route.params.slug))
const { t } = useI18n()

useHead({ title: () => `${t('scopeGuide.team.title')} | Mannschaft` })
</script>

<template>
  <ScopeGuideContent scope-type="team" :slug="slug" />
</template>
