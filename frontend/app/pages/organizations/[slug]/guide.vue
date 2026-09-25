<script setup lang="ts">
definePageMeta({ middleware: ['auth', 'org-role-guard'], layout: 'default' })

const route = useRoute()
const slug = computed(() => String(route.params.slug))
const { t } = useI18n()

useHead({ title: () => `${t('scopeGuide.organization.title')} | Mannschaft` })
</script>

<template>
  <ScopeGuideContent scope-type="organization" :slug="slug" />
</template>
