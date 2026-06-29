<script setup lang="ts">
import type { UserAdPreferences } from '~/types/adPreferences'

/**
 * F09.17 受信者向け広告受信設定ページ
 *
 * 機能:
 * - 4 チャネル別トグル（announcement / email / push / banner）
 * - 広告主ブロックリスト UI（追加・削除、最大 100 件）
 * - unsubscribe_token_version 強制ローテボタン
 * - 422 (blocked 100 件超過) / 429 (レート制限) の専用エラーハンドリング
 */

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const notification = useNotification()
const prefsApi = useAdPreferencesApi()
const confirm = useConfirm()
const { formatDateTime } = useDatetime()

const preferences = ref<UserAdPreferences | null>(null)
const loading = ref(false)
const saving = ref(false)
const rotating = ref(false)
const showGuide = ref(false)

const newBlockId = ref<string>('')

async function loadPreferences() {
  loading.value = true
  try {
    const res = await prefsApi.getPreferences()
    preferences.value = res.data
  } catch (err) {
    notification.error(t('advertising.pages.settings_ad_preferences.title'), String(err))
  } finally {
    loading.value = false
  }
}

interface FetchError {
  statusCode?: number
  response?: { status?: number }
}

function extractStatus(err: unknown): number | undefined {
  if (typeof err !== 'object' || err === null) return undefined
  const e = err as FetchError
  return e.statusCode ?? e.response?.status
}

async function savePreferences() {
  if (!preferences.value) return
  saving.value = true
  try {
    const res = await prefsApi.updatePreferences({
      acceptAnnouncementAds: preferences.value.acceptAnnouncementAds,
      acceptEmailAds: preferences.value.acceptEmailAds,
      acceptPushAds: preferences.value.acceptPushAds,
      acceptBannerAds: preferences.value.acceptBannerAds,
      blockedAdvertiserAccountIds: preferences.value.blockedAdvertiserAccountIds,
    })
    preferences.value = res.data
    notification.success(t('advertising.pages.settings_ad_preferences.saved'))
  } catch (err) {
    const status = extractStatus(err)
    if (status === 422) {
      notification.error(
        t('advertising.pages.settings_ad_preferences.blocked_limit_exceeded'),
      )
    } else if (status === 429) {
      notification.warn(t('advertising.pages.settings_ad_preferences.save_rate_limited'))
    } else {
      notification.error(
        t('advertising.pages.settings_ad_preferences.title'),
        String(err),
      )
    }
  } finally {
    saving.value = false
  }
}

function addBlock() {
  if (!preferences.value) return
  const id = Number(newBlockId.value.trim())
  if (!Number.isInteger(id) || id <= 0) return
  if (preferences.value.blockedAdvertiserAccountIds.includes(id)) {
    newBlockId.value = ''
    return
  }
  if (preferences.value.blockedAdvertiserAccountIds.length >= 100) {
    notification.warn(t('advertising.pages.settings_ad_preferences.blocked_limit_exceeded'))
    return
  }
  preferences.value = {
    ...preferences.value,
    blockedAdvertiserAccountIds: [
      ...preferences.value.blockedAdvertiserAccountIds,
      id,
    ],
  }
  newBlockId.value = ''
}

function removeBlock(id: number) {
  if (!preferences.value) return
  preferences.value = {
    ...preferences.value,
    blockedAdvertiserAccountIds: preferences.value.blockedAdvertiserAccountIds.filter(
      (x) => x !== id,
    ),
  }
}

function handleRotateTokens() {
  confirm.require({
    message: t('advertising.pages.settings_ad_preferences.rotate_tokens_confirm'),
    header: t('advertising.pages.settings_ad_preferences.rotate_tokens_section'),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: t('advertising.report_dialog.cancel'),
    acceptLabel: t('advertising.pages.settings_ad_preferences.rotate_tokens'),
    accept: async () => {
      rotating.value = true
      try {
        const res = await prefsApi.rotateUnsubscribeTokens()
        preferences.value = res.data
        notification.success(
          t('advertising.pages.settings_ad_preferences.rotate_tokens_success'),
        )
      } catch (err) {
        notification.error(
          t('advertising.pages.settings_ad_preferences.rotate_tokens_section'),
          String(err),
        )
      } finally {
        rotating.value = false
      }
    },
  })
}

const blockedCount = computed(
  () => preferences.value?.blockedAdvertiserAccountIds.length ?? 0,
)
const blockLimitReached = computed(() => blockedCount.value >= 100)

const formattedConsentedAt = computed(() => {
  const v = preferences.value?.consentedAt
  if (!v) return t('advertising.pages.settings_ad_preferences.not_consented')
  return formatDateTime(v)
})

onMounted(loadPreferences)
</script>

<template>
  <div>
    <div class="mx-auto max-w-2xl p-6">
      <PageHeader :title="t('advertising.pages.settings_ad_preferences.title')" help @help="showGuide = true" />
      <p class="mb-6 text-sm text-surface-500 dark:text-surface-300">
        {{ t('advertising.pages.settings_ad_preferences.description') }}
      </p>

      <PageLoading v-if="loading" />

      <div v-else-if="preferences" class="flex flex-col gap-6">
        <!-- チャネル別設定 -->
        <SectionCard>
          <template #header>
            <h2 class="text-base font-semibold">
              {{ t('advertising.pages.settings_ad_preferences.channel_section') }}
            </h2>
          </template>
          <p class="mb-4 text-xs text-surface-500 dark:text-surface-300">
            {{ t('advertising.pages.settings_ad_preferences.channel_section_description') }}
          </p>

          <div class="flex flex-col gap-3">
            <label class="flex items-center justify-between">
              <span>{{ t('advertising.pages.settings_ad_preferences.channel_announcement') }}</span>
              <InputSwitch
                v-model="preferences.acceptAnnouncementAds"
                data-testid="toggle-announcement"
              />
            </label>
            <label class="flex items-center justify-between">
              <span>{{ t('advertising.pages.settings_ad_preferences.channel_email') }}</span>
              <InputSwitch
                v-model="preferences.acceptEmailAds"
                data-testid="toggle-email"
              />
            </label>
            <label class="flex items-center justify-between">
              <span>{{ t('advertising.pages.settings_ad_preferences.channel_push') }}</span>
              <InputSwitch
                v-model="preferences.acceptPushAds"
                data-testid="toggle-push"
              />
            </label>
            <label class="flex items-center justify-between">
              <span>{{ t('advertising.pages.settings_ad_preferences.channel_banner') }}</span>
              <InputSwitch
                v-model="preferences.acceptBannerAds"
                data-testid="toggle-banner"
              />
            </label>
          </div>

          <div class="mt-4 text-xs text-surface-400">
            {{ t('advertising.pages.settings_ad_preferences.consented_at') }}:
            {{ formattedConsentedAt }}
          </div>
        </SectionCard>

        <!-- ブロックリスト -->
        <SectionCard>
          <template #header>
            <h2 class="text-base font-semibold">
              {{ t('advertising.pages.settings_ad_preferences.blocked_section') }}
              <span class="ml-2 text-xs font-normal text-surface-400">
                ({{ blockedCount }} / 100)
              </span>
            </h2>
          </template>
          <p class="mb-4 text-xs text-surface-500 dark:text-surface-300">
            {{ t('advertising.pages.settings_ad_preferences.blocked_section_description') }}
          </p>

          <div class="mb-4 flex gap-2">
            <label class="sr-only" for="block-id-input">
              {{ t('advertising.pages.settings_ad_preferences.add_block_label') }}
            </label>
            <InputText
              id="block-id-input"
              v-model="newBlockId"
              type="number"
              :placeholder="t('advertising.pages.settings_ad_preferences.add_block_placeholder')"
              :disabled="blockLimitReached"
              class="flex-1"
              data-testid="block-id-input"
            />
            <Button
              :label="t('advertising.pages.settings_ad_preferences.add_block_button')"
              icon="pi pi-plus"
              :disabled="blockLimitReached || !newBlockId.trim()"
              data-testid="add-block-button"
              @click="addBlock"
            />
          </div>

          <p
            v-if="blockLimitReached"
            class="mb-4 rounded bg-orange-100 p-2 text-xs text-orange-700 dark:bg-orange-900/30 dark:text-orange-300"
          >
            {{ t('advertising.pages.settings_ad_preferences.blocked_limit_exceeded') }}
          </p>

          <ul v-if="blockedCount > 0" class="flex flex-col gap-2" data-testid="block-list">
            <li
              v-for="id in preferences.blockedAdvertiserAccountIds"
              :key="id"
              class="flex items-center justify-between rounded border border-surface-300 px-3 py-2 dark:border-surface-600"
            >
              <span class="text-sm">#{{ id }}</span>
              <Button
                icon="pi pi-times"
                severity="danger"
                text
                rounded
                size="small"
                :aria-label="t('advertising.actions.unblock_advertiser')"
                @click="removeBlock(id)"
              />
            </li>
          </ul>
          <p v-else class="text-sm text-surface-400">
            {{ t('advertising.pages.settings_ad_preferences.blocked_empty') }}
          </p>
        </SectionCard>

        <!-- セキュリティ (token rotate) -->
        <SectionCard>
          <template #header>
            <h2 class="text-base font-semibold">
              {{ t('advertising.pages.settings_ad_preferences.rotate_tokens_section') }}
            </h2>
          </template>
          <p class="mb-4 text-xs text-surface-500 dark:text-surface-300">
            {{ t('advertising.pages.settings_ad_preferences.rotate_tokens_help') }}
          </p>
          <Button
            :label="t('advertising.pages.settings_ad_preferences.rotate_tokens')"
            icon="pi pi-refresh"
            severity="warn"
            :loading="rotating"
            data-testid="rotate-tokens-button"
            @click="handleRotateTokens"
          />
        </SectionCard>

        <!-- 保存ボタン -->
        <div class="flex justify-end">
          <Button
            :label="t('advertising.pages.settings_ad_preferences.save_button')"
            icon="pi pi-save"
            :loading="saving"
            data-testid="save-button"
            @click="savePreferences"
          />
        </div>
      </div>
    </div>

    <ConfirmDialog />
    <AdPreferencesGuideModal v-model:visible="showGuide" />
  </div>
</template>
