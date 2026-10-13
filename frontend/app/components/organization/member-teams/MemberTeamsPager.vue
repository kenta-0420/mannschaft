<script setup lang="ts">
/** member-teams 各 view 共通の前へ／次へ。ページが 1 つなら何も描画しない。page は 0 始まり。 */
defineProps<{
  totalPages: number
}>()

const page = defineModel<number>('page', { required: true })
</script>

<template>
  <div v-if="totalPages > 1" class="mt-4 flex items-center justify-center gap-3" data-testid="pager">
    <Button
      :label="$t('orgMemberTeams.pager.prev')"
      icon="pi pi-chevron-left"
      text
      size="small"
      :disabled="page <= 0"
      data-testid="pager-prev"
      @click="page -= 1"
    />
    <span class="text-sm text-surface-500">
      {{ $t('orgMemberTeams.pager.info', { page: page + 1, total: totalPages }) }}
    </span>
    <Button
      :label="$t('orgMemberTeams.pager.next')"
      icon="pi pi-chevron-right"
      icon-pos="right"
      text
      size="small"
      :disabled="page + 1 >= totalPages"
      data-testid="pager-next"
      @click="page += 1"
    />
  </div>
</template>
