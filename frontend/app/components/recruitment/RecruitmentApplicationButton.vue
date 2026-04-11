<script setup lang="ts">
import { computed, ref } from 'vue'
import type { RecruitmentListingResponse } from '~/types/recruitment'
import RecruitmentCancellationConfirmModal from './RecruitmentCancellationConfirmModal.vue'

interface Props {
  listing: RecruitmentListingResponse
  myParticipantId?: number | null
}
const props = defineProps<Props>()
const emit = defineEmits<{
  applied: []
  cancelled: []
}>()

const { t } = useI18n()
const api = useRecruitmentApi()
const { error, success } = useNotification()

const loading = ref(false)
const showCancelModal = ref(false)
const estimate = ref<Awaited<ReturnType<typeof api.estimateCancellationFee>>['data'] | null>(null)

const isFull = computed(() => props.listing.status === 'FULL')
const canApply = computed(() => {
  return (props.listing.status === 'OPEN' || props.listing.status === 'FULL')
    && props.myParticipantId == null
})

async function onApply() {
  if (!canApply.value) return
  loading.value = true
  try {
    await api.applyToListing(props.listing.id, {
      participantType: 'USER',
    })
    success(t('recruitment.action.apply'))
    emit('applied')
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

async function openCancelConfirm() {
  loading.value = true
  try {
    const result = await api.estimateCancellationFee(props.listing.id)
    estimate.value = result.data
    showCancelModal.value = true
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

async function onAgreeCancel() {
  if (!estimate.value) return
  loading.value = true
  try {
    await api.cancelMyApplication(props.listing.id, {
      acknowledgedFee: true,
      feeAmountAtRequest: estimate.value.feeAmount,
    })
    success(t('recruitment.action.cancelMyApplication'))
    showCancelModal.value = false
    emit('cancelled')
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <div>
    <Button
      v-if="canApply && !isFull"
      :label="t('recruitment.action.apply')"
      icon="pi pi-check"
      :loading="loading"
      @click="onApply"
    />
    <Button
      v-else-if="canApply && isFull"
      :label="t('recruitment.action.joinWaitlist')"
      icon="pi pi-clock"
      severity="warn"
      :loading="loading"
      @click="onApply"
    />
    <Button
      v-else-if="myParticipantId"
      :label="t('recruitment.action.cancelMyApplication')"
      icon="pi pi-times"
      severity="secondary"
      :loading="loading"
      @click="openCancelConfirm"
    />

    <RecruitmentCancellationConfirmModal
      v-model:visible="showCancelModal"
      :estimate="estimate"
      :loading="loading"
      @agree="onAgreeCancel"
    />
  </div>
</template>
