<script setup lang="ts">
const props = defineProps<{
  swapRequestId: number
  /** すでに候補者が決定している場合は true */
  isFull?: boolean
  /** 自分自身がすでに手を挙げている場合は true */
  hasClaimed?: boolean
}>()

const emit = defineEmits<{
  claimed: []
}>()

const { t } = useI18n()
const { claim } = useOpenCall()
const isClaiming = ref(false)

async function onClaim(): Promise<void> {
  isClaiming.value = true
  try {
    await claim(props.swapRequestId)
    emit('claimed')
  } finally {
    isClaiming.value = false
  }
}

const buttonLabel = computed(() => {
  if (props.hasClaimed) return t('shift.openCall.claimed')
  if (props.isFull) return t('shift.openCall.full')
  return t('shift.openCall.claim')
})

const isDisabled = computed(() => props.isFull || props.hasClaimed || isClaiming.value)
</script>

<template>
  <div class="flex items-center gap-2">
    <Tag
      :value="$t('shift.openCall.badge')"
      severity="warning"
      icon="pi pi-megaphone"
    />
    <Button
      :label="buttonLabel"
      :loading="isClaiming"
      :disabled="isDisabled"
      size="small"
      severity="warning"
      @click="onClaim"
    />
  </div>
</template>
