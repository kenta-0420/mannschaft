<script setup lang="ts">
/**
 * オーナー委譲オファーの承諾/辞退カード（受信側・F01.2 承諾型化）。
 *
 * # 設置場所についての注記（未解決点）
 * BE には現時点でオファーの一覧/詳細取得 API が無く（打診時のレスポンスでしか offerId を知り得ない）、
 * 打診時の通知（F04.3/F04.9）も本 Service からは発火されていない（`OwnershipTransferOfferService` に
 * 通知呼び出しなし）。そのため本カードは「`?offerId=` クエリ付きで `/teams/{slug}/members` /
 * `/organizations/{slug}/members` を開くと表示される」設計とし、既存の通知一覧
 * （`components/notification/NotificationList.vue` の `onClickNotification` → `router.push(actionUrl)`）
 * が将来 `actionUrl` にこの形式のディープリンクを設定すれば、そのままクリックで到達できるようにしている。
 * BE 側で通知発火（`action_url` 設定）または一覧 API の追加が必要（後続課題）。
 */

const props = defineProps<{
  scopeType: 'team' | 'organization'
  slug: string
  offerId: string
  /** バナー文言に埋め込むスコープ表示名（チーム名/組織名）。 */
  scopeName?: string
}>()

const emit = defineEmits<{
  resolved: ['accepted' | 'declined']
}>()

const { t } = useI18n()
const notification = useNotification()
const { confirmAction } = useConfirmDialog()
const teamApi = useTeamApi()
const orgApi = useOrganizationApi()

const accepting = ref(false)
const declining = ref(false)
/** 422（ROLE_010・2FA 未設定）専用の状態。トーストで流さず CTA 付きの帯で常時表示する（設計書 U2）。 */
const need2fa = ref(false)
/** それ以外のエラー（403 宛先不一致・409 状態不整合等）。 */
const errorMessage = ref<string | null>(null)
const resultMessage = ref<string | null>(null)

function extractErrorCode(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string; message?: string } } }
  return apiError?.data?.error?.code
}

function extractErrorMessage(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string; message?: string } } }
  return apiError?.data?.error?.message
}

function onAcceptClick() {
  confirmAction({
    header: t('role.transfer.offer.acceptConfirmTitle'),
    message: t('role.transfer.offer.acceptConfirmMessage'),
    onAccept: doAccept,
  })
}

async function doAccept() {
  accepting.value = true
  need2fa.value = false
  errorMessage.value = null
  try {
    const accept = props.scopeType === 'team' ? teamApi.acceptOwnershipOffer : orgApi.acceptOwnershipOffer
    await accept(props.slug, props.offerId)
    resultMessage.value = t('role.transfer.offer.accepted')
    notification.success(t('role.transfer.offer.accepted'))
    emit('resolved', 'accepted')
  }
  catch (error) {
    const code = extractErrorCode(error)
    if (code === 'ROLE_010') {
      // 2FA 未設定（422）。トーストに留めず CTA 付きの帯を表示し続ける（設計書 U2）。
      // 設定後に URL の ?offerId= はそのまま残るため、再訪して再度「引き受ける」を押せる。
      need2fa.value = true
    }
    else if (code === 'ROLE_009') {
      errorMessage.value = t('role.transfer.error.notTarget')
    }
    else if (code === 'ROLE_012') {
      errorMessage.value = t('role.transfer.offer.expired')
    }
    else {
      errorMessage.value = extractErrorMessage(error) ?? t('error.unknown')
    }
  }
  finally {
    accepting.value = false
  }
}

function onDeclineClick() {
  confirmAction({
    header: t('role.transfer.offer.declineConfirmTitle'),
    message: t('role.transfer.offer.declineConfirmMessage'),
    onAccept: doDecline,
  })
}

async function doDecline() {
  declining.value = true
  errorMessage.value = null
  try {
    const decline = props.scopeType === 'team' ? teamApi.declineOwnershipOffer : orgApi.declineOwnershipOffer
    await decline(props.slug, props.offerId)
    resultMessage.value = t('role.transfer.offer.declined')
    notification.success(t('role.transfer.offer.declined'))
    emit('resolved', 'declined')
  }
  catch (error) {
    const code = extractErrorCode(error)
    if (code === 'ROLE_009') {
      errorMessage.value = t('role.transfer.error.notTarget')
    }
    else if (code === 'ROLE_012') {
      errorMessage.value = t('role.transfer.offer.expired')
    }
    else {
      errorMessage.value = extractErrorMessage(error) ?? t('error.unknown')
    }
  }
  finally {
    declining.value = false
  }
}

function goToSecuritySettings() {
  navigateTo('/settings/security')
}
</script>

<template>
  <SectionCard v-if="!resultMessage">
    <template #header>
      <div class="flex items-center gap-2">
        <i class="pi pi-crown text-lg text-amber-500" />
        <h2 class="text-lg font-semibold tracking-tight">
          {{ t('role.transfer.offer.pending') }}
        </h2>
      </div>
    </template>

    <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
      {{ scopeName ? t('role.transfer.offer.notification', { scope: scopeName }) : t('role.transfer.offer.pending') }}
    </p>

    <!-- 2FA 未設定 CTA 帯（設計書 U2） -->
    <Message v-if="need2fa" severity="warn" :closable="false" class="mb-4">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <span>{{ t('role.transfer.error.need2fa.message') }}</span>
        <Button
          :label="t('role.transfer.error.need2fa.cta')"
          size="small"
          severity="warn"
          @click="goToSecuritySettings"
        />
      </div>
    </Message>

    <Message v-else-if="errorMessage" severity="error" :closable="false" class="mb-4">
      {{ errorMessage }}
    </Message>

    <div class="flex justify-end gap-2">
      <Button
        :label="t('role.transfer.offer.decline')"
        severity="secondary"
        text
        :loading="declining"
        :disabled="accepting"
        @click="onDeclineClick"
      />
      <Button
        :label="t('role.transfer.offer.accept')"
        severity="warn"
        icon="pi pi-check"
        :loading="accepting"
        :disabled="declining"
        @click="onAcceptClick"
      />
    </div>
  </SectionCard>

  <SectionCard v-else>
    <p class="text-sm text-surface-600 dark:text-surface-300">
      {{ resultMessage }}
    </p>
  </SectionCard>
</template>
