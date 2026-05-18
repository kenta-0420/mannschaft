<script setup lang="ts">
/**
 * F09.17 初回広告同意ダイアログ
 *
 * 表示戦略: on-demand
 *   お知らせフィードページの初回マウント時 + `isAdvertisement: true` の項目が含まれる場合
 *   + `localStorage` フラグ未セット のときに表示。
 *
 * 殿の判断（指示書）に従い、グローバルマウントは行わない。
 *
 * 2 つの選択肢:
 *   1. 「同意して続行」 → フラグをセットして閉じるのみ
 *   2. 「広告を全停止」 → PUT /api/v1/me/ad-preferences で全チャネル false にして閉じる
 */

const CONSENT_FLAG_KEY = 'mannschaft.ad.consent.shown'

defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const { t } = useI18n()
const notification = useNotification()
const prefsApi = useAdPreferencesApi()

const stopping = ref(false)

function markShown() {
  if (import.meta.client) {
    try {
      window.localStorage.setItem(CONSENT_FLAG_KEY, '1')
    } catch {
      // localStorage 利用不可（プライベートブラウジング等）でも継続
    }
  }
}

function handleAgree() {
  markShown()
  emit('update:visible', false)
}

async function handleStopAll() {
  stopping.value = true
  try {
    await prefsApi.updatePreferences({
      acceptAnnouncementAds: false,
      acceptEmailAds: false,
      acceptPushAds: false,
      acceptBannerAds: false,
    })
    notification.success(t('advertising.consent_dialog.stopped_message'))
    markShown()
    emit('update:visible', false)
  } catch (err) {
    notification.error(
      t('advertising.consent_dialog.title'),
      String(err),
    )
  } finally {
    stopping.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="t('advertising.consent_dialog.title')"
    :style="{ width: '32rem' }"
    :breakpoints="{ '640px': '90vw' }"
    :closable="false"
    @update:visible="(v) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-3">
      <p class="text-sm leading-relaxed">
        {{ t('advertising.consent_dialog.description') }}
      </p>
    </div>

    <template #footer>
      <Button
        :label="t('advertising.consent_dialog.stop_all')"
        severity="secondary"
        text
        :loading="stopping"
        :disabled="stopping"
        @click="handleStopAll"
      />
      <Button
        :label="t('advertising.consent_dialog.agree')"
        icon="pi pi-check"
        :disabled="stopping"
        @click="handleAgree"
      />
    </template>
  </Dialog>
</template>

<script lang="ts">
/**
 * 同意ダイアログ表示判定ヘルパー（onMounted 等から呼ぶ）。
 *
 * @returns `true` ならまだ表示されていない（要表示）、`false` なら既に表示済み
 */
export function shouldShowAdConsentDialog(): boolean {
  if (typeof window === 'undefined') return false
  try {
    return window.localStorage.getItem('mannschaft.ad.consent.shown') !== '1'
  } catch {
    return false
  }
}
</script>
