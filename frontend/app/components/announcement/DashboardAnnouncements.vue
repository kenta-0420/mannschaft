<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'
import { toDashboardAnnouncementItem } from '~/utils/announcementAdapter'

const props = defineProps<{
  scopeType: AnnouncementScopeType
  scopeId: string
  scopeSlug: string
  items: Record<string, unknown>[]
}>()
const emit = defineEmits<{ refresh: []; unavailable: [id: number] }>()
const converted = computed(() => {
  try { return { items: props.items.map(toDashboardAnnouncementItem), error: null } }
  catch (error) { return { items: [], error } }
})
</script>

<template>
  <DashboardWidgetCard v-if="converted.error">
    <DashboardErrorState :error="converted.error" @retry="emit('refresh')" />
  </DashboardWidgetCard>
  <WidgetAnnouncements
    v-else
    :scope-type="scopeType"
    :scope-id="scopeId"
    :scope-slug="scopeSlug"
    :initial-items="converted.items"
    @refresh="emit('refresh')"
    @unavailable="emit('unavailable', $event)"
  />
</template>
