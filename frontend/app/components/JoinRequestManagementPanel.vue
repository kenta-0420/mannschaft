<script setup lang="ts">
import type { JoinRequestScopeType } from '~/composables/useJoinRequestApi'
import JoinRequestList from '~/components/joinRequest/JoinRequestList.vue'

const props = defineProps<{
  scopeType: JoinRequestScopeType
  scopeId: number
}>()

const {
  requests,
  requestsLoading,
  requestsError,
  processingIds,
  totalElements,
  hasMore,
  approve,
  reject,
  loadMore,
  retry,
  init,
} = useJoinRequestManagement(toRef(props, 'scopeType'), toRef(props, 'scopeId'))

onMounted(init)
</script>

<template>
  <div class="space-y-6">
    <JoinRequestList
      :requests="requests"
      :processing-ids="processingIds"
      :loading="requestsLoading"
      :error="requestsError"
      :total-elements="totalElements"
      :has-more="hasMore"
      @approve="approve"
      @reject="reject"
      @load-more="loadMore"
      @retry="retry"
    />
  </div>
</template>
