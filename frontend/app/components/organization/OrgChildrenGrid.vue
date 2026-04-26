<script setup lang="ts">
import type { ChildOrganization } from '~/types/organization'

defineProps<{
  children: ChildOrganization[]
  loading: boolean
  hasNext: boolean
}>()

const emit = defineEmits<{
  (e: 'loadMore'): void
}>()

const { t } = useI18n()

function onLoadMore() {
  emit('loadMore')
}
</script>

<template>
  <div class="mt-4">
    <div
      v-if="children.length === 0 && !loading"
      class="rounded-lg border border-dashed border-gray-300 p-8 text-center text-gray-500 dark:border-surface-700 dark:text-surface-400"
      data-testid="org-children-empty"
    >
      <i class="pi pi-sitemap mb-2 text-3xl" />
      <p>{{ t('organization.no_children') }}</p>
    </div>

    <div
      v-else
      class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
      data-testid="org-children-grid"
    >
      <div
        v-for="child in children"
        :key="child.id"
        class="cursor-pointer rounded-lg border p-4 transition-shadow hover:shadow-md dark:border-surface-700"
        data-testid="org-child-card"
        @click="navigateTo(`/organizations/${child.id}`)"
      >
        <div class="mb-2 flex items-center gap-3">
          <Avatar
            :image="child.iconUrl ?? undefined"
            :label="child.iconUrl ? undefined : (child.nickname1 || child.name).charAt(0)"
            shape="circle"
          />
          <div class="min-w-0 flex-1">
            <h3 class="truncate font-semibold">
              {{ child.nickname1 || child.name }}
            </h3>
            <Badge
              v-if="child.archived"
              :value="t('organization.archived')"
              severity="warn"
              data-testid="org-child-archived"
            />
          </div>
        </div>
        <div class="text-sm text-gray-500 dark:text-surface-400">
          <i class="pi pi-users mr-1" />{{ child.memberCount }}
        </div>
      </div>
    </div>

    <div v-if="hasNext" class="mt-4 flex justify-center">
      <Button
        :label="t('organization.load_more')"
        icon="pi pi-chevron-down"
        size="small"
        outlined
        :loading="loading"
        data-testid="org-children-load-more"
        @click="onLoadMore"
      />
    </div>
  </div>
</template>
