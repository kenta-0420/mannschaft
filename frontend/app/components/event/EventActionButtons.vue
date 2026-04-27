<script setup lang="ts">
import type { DismissalStatusResponse } from '~/types/care'

const props = defineProps<{
  status: string
  /** F03.12 §16 解散通知ステータス（チームイベント時のみ意味あり）。 */
  dismissalStatus?: DismissalStatusResponse | null
  /** 解散ボタンの表示を許可するか（scopeType==='team' のときのみ true）。 */
  showDismissalButton?: boolean
}>()

defineEmits<{
  edit: []
  publish: []
  closeRegistration: []
  openRegistration: []
  cancel: []
  /** F03.12 §16 解散通知を送信する。 */
  dismiss: []
}>()

/**
 * 解散ボタンを表示するか。
 *
 * <p>F03.12 §16 仕様: チームイベントかつ未送信状態（{@code dismissed===false}）のときのみ表示する。
 * dismissalStatus が未取得（null）でも、ステータス API が応答するまでボタンは出さない。</p>
 */
const showDismiss = computed(
  () =>
    props.showDismissalButton === true
    && props.dismissalStatus != null
    && props.dismissalStatus.dismissed === false,
)
</script>

<template>
  <div class="flex gap-2">
    <Button label="編集" icon="pi pi-pencil" outlined @click="$emit('edit')" />
    <Button
      v-if="status === 'DRAFT'"
      label="公開"
      icon="pi pi-send"
      severity="success"
      @click="$emit('publish')"
    />
    <Button
      v-if="status === 'PUBLISHED'"
      label="受付終了"
      icon="pi pi-lock"
      severity="warn"
      @click="$emit('closeRegistration')"
    />
    <Button
      v-if="status === 'CLOSED'"
      label="受付再開"
      icon="pi pi-lock-open"
      severity="info"
      @click="$emit('openRegistration')"
    />
    <!-- F03.12 §16 解散通知ボタン -->
    <Button
      v-if="showDismiss"
      :label="$t('event.dismissal.send_button')"
      icon="pi pi-megaphone"
      severity="primary"
      data-testid="event-dismissal-button"
      @click="$emit('dismiss')"
    />
    <Button
      v-if="status !== 'CANCELLED'"
      label="キャンセル"
      icon="pi pi-times"
      severity="danger"
      outlined
      @click="$emit('cancel')"
    />
  </div>
</template>
