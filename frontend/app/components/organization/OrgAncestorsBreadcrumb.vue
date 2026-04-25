<script setup lang="ts">
import type { AncestorOrganization } from '~/types/organization'

defineProps<{
  ancestors: AncestorOrganization[]
  currentOrgName: string
}>()

const { t } = useI18n()

/** 祖先の表示名（nickname1 優先） */
function displayName(a: AncestorOrganization): string {
  return a.nickname1 || a.name || ''
}
</script>

<template>
  <nav
    v-if="ancestors.length > 0"
    class="flex items-center gap-1 overflow-x-auto whitespace-nowrap text-sm text-surface-500 dark:text-surface-400"
    :aria-label="t('organization.parent_chain')"
    data-testid="org-ancestors-breadcrumb"
  >
    <template v-for="(ancestor, index) in ancestors" :key="ancestor.id">
      <!-- 非公開（プレースホルダ） -->
      <span
        v-if="ancestor.hidden"
        class="inline-flex items-center gap-1 rounded bg-surface-200 px-2 py-0.5 text-xs text-surface-500 dark:bg-surface-700 dark:text-surface-400"
        data-testid="org-ancestor-hidden"
      >
        <i class="pi pi-lock text-xs" />
        {{ t('organization.hidden_org') }}
      </span>

      <!-- 通常の祖先（リンク） -->
      <NuxtLink
        v-else
        :to="`/organizations/${ancestor.id}`"
        class="inline-flex items-center gap-1 hover:text-primary hover:underline dark:hover:text-primary-300"
        data-testid="org-ancestor-link"
      >
        <Avatar
          v-if="ancestor.iconUrl"
          :image="ancestor.iconUrl"
          shape="circle"
          size="normal"
          class="!h-4 !w-4"
        />
        <span>{{ displayName(ancestor) }}</span>
      </NuxtLink>

      <!-- セパレーター -->
      <i
        v-if="index < ancestors.length"
        class="pi pi-angle-right text-xs text-surface-400 dark:text-surface-500"
        aria-hidden="true"
      />
    </template>

    <!-- 現組織（リンクなし・太字） -->
    <strong
      class="text-surface-900 dark:text-surface-100"
      data-testid="org-ancestor-current"
    >
      {{ currentOrgName }}
    </strong>
  </nav>
</template>
