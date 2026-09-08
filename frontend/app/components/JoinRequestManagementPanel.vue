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
  processingIds,
  approve,
  reject,
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
      @approve="approve"
      @reject="reject"
    />
  </div>
</template>
