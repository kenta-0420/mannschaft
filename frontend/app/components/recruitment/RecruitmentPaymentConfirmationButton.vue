<script setup lang="ts">
/**
 * 有料募集の応募者（支払者）が謝礼のカード与信を確認するための導線。
 *
 * 応募直後は autoOpen でダイアログを開き、非同期のエスクロー起票待ちは
 * MarketEscrowConfirmDialog の再試行表示に委ねる。再訪時にもボタンを残すことで、
 * 3DS 復帰やキャンセル待ちからの昇格後に支払い確認を再開できる。
 */
import Button from 'primevue/button'

interface Props {
  listingId: number
  participantId: number
  autoOpen?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  autoOpen: false,
})

const emit = defineEmits<{
  confirmed: [escrowTransactionId: string]
}>()

const { t } = useI18n()
const dialogVisible = ref(false)

function openDialog() {
  dialogVisible.value = true
}

function onConfirmed(escrowTransactionId: string) {
  emit('confirmed', escrowTransactionId)
}

onMounted(() => {
  if (props.autoOpen) {
    openDialog()
  }
})
</script>

<template>
  <div>
    <Button
      :label="t('market.payment.confirm.title')"
      icon="pi pi-credit-card"
      severity="secondary"
      data-testid="recruitment-payment-confirm-button"
      @click="openDialog"
    />

    <MarketEscrowConfirmDialog
      v-model:visible="dialogVisible"
      :listing-id="listingId"
      :participant-id="participantId"
      @confirmed="onConfirmed"
    />
  </div>
</template>
