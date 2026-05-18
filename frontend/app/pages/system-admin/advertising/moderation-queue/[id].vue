<script setup lang="ts">
/**
 * F09.17 Phase 11-c-4 — SYSTEM_ADMIN 広告キャンペーン審査詳細。
 *
 * <p>1 キャンペーンの全チャネル本文をロケール切替で表示し、検出された NG ワードを
 * 赤ハイライト表示する。承認 / ブロックボタンを備える（ブロック時は理由必須）。</p>
 */
import type { AdMessagingCampaignChannel } from '~/types/adMessagingCampaign'
import type { AdReviewCampaignDetail } from '~/composables/useSystemAdminAdCampaignApi'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const systemAdminAdApi = useSystemAdminAdCampaignApi()
const notification = useNotification()

const campaignId = computed(() => String(route.params.id))

const loading = ref(true)
const detail = ref<AdReviewCampaignDetail | null>(null)
const previewLocale = ref<string>('ja')
const approveSubmitting = ref(false)
const blockSubmitting = ref(false)
const blockDialogOpen = ref(false)
const blockReason = ref('')

const availableLocales = computed(() => {
  if (!detail.value) return []
  const locales = new Set<string>()
  for (const channel of detail.value.campaign.channels) {
    locales.add(channel.locale)
  }
  return Array.from(locales)
})

watchEffect(() => {
  // 利用可能 locale が変わった場合、現在の previewLocale が無効なら ja か最初の locale にする
  if (availableLocales.value.length === 0) return
  if (!availableLocales.value.includes(previewLocale.value)) {
    previewLocale.value = availableLocales.value.includes('ja')
      ? 'ja'
      : (availableLocales.value[0] ?? 'ja')
  }
})

const channelsForCurrentLocale = computed<AdMessagingCampaignChannel[]>(() => {
  if (!detail.value) return []
  return detail.value.campaign.channels.filter(
    (c) => c.locale === previewLocale.value,
  )
})

/**
 * NG ワード一覧で出現する語句を本文中で赤ハイライトした HTML を返す。
 *
 * <p>あくまでサニタイズ済みのテキスト（subject / bodyMarkdown）を対象とする想定。
 * XSS 対策として、まず HTML エスケープしたうえで NG ワードの前後を span で包む。</p>
 */
function highlightNgWords(text: string | null | undefined): string {
  if (!text) return ''
  const escaped = escapeHtml(text)
  const ngWords = detail.value?.detectedNgWords ?? []
  if (ngWords.length === 0) return escaped
  // 長い語句から置換することで部分一致の衝突を回避
  const sorted = [...ngWords].sort((a, b) => b.length - a.length)
  let result = escaped
  for (const w of sorted) {
    if (!w) continue
    const escW = escapeHtml(w)
    const re = new RegExp(escapeRegex(escW), 'gi')
    result = result.replace(
      re,
      '<span class="rounded bg-red-100 px-1 font-semibold text-red-700 dark:bg-red-900/40 dark:text-red-200">$&</span>',
    )
  }
  return result
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

async function load() {
  loading.value = true
  try {
    const res = await systemAdminAdApi.getCampaignForReview(campaignId.value)
    detail.value = res.data
  } catch {
    notification.error(t('advertising.pages.system_admin_dashboard.load_failed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function handleApprove() {
  // ConfirmDialog 互換のため簡易 confirm を使用（root cause fix が必要なら ConfirmDialog にする）
  if (!window.confirm(t('advertising.pages.system_admin_moderation_detail.approve_confirm'))) {
    return
  }
  approveSubmitting.value = true
  try {
    await systemAdminAdApi.approveCampaign(campaignId.value)
    notification.success(t('advertising.pages.system_admin_moderation_detail.approved_toast'))
    router.push('/system-admin/advertising/moderation-queue')
  } catch {
    notification.error(t('advertising.error_codes.AD_CAMPAIGN_INVALID_TRANSITION'))
  } finally {
    approveSubmitting.value = false
  }
}

function openBlockDialog() {
  blockReason.value = ''
  blockDialogOpen.value = true
}

async function handleBlockSubmit() {
  if (!blockReason.value.trim()) {
    return
  }
  blockSubmitting.value = true
  try {
    await systemAdminAdApi.blockCampaign(campaignId.value, { reason: blockReason.value.trim() })
    notification.success(t('advertising.pages.system_admin_moderation_detail.blocked_toast'))
    blockDialogOpen.value = false
    router.push('/system-admin/advertising/moderation-queue')
  } catch {
    notification.error(t('advertising.error_codes.AD_CAMPAIGN_INVALID_TRANSITION'))
  } finally {
    blockSubmitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl">
    <div class="mb-6 flex items-center gap-3">
      <Button
        :label="t('advertising.actions.back_to_queue')"
        icon="pi pi-arrow-left"
        severity="secondary"
        text
        @click="router.push('/system-admin/advertising/moderation-queue')"
      />
      <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
        {{ t('advertising.pages.system_admin_moderation_detail.title') }}
      </h1>
    </div>

    <PageLoading v-if="loading" />

    <template v-else-if="detail">
      <!-- サマリ -->
      <section class="mb-6 rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800">
        <div class="flex flex-wrap items-center gap-3">
          <h2 class="text-lg font-semibold text-surface-900 dark:text-surface-50">
            {{ detail.campaign.name }}
          </h2>
          <Tag :value="t(`advertising.status.${detail.campaign.status.toLowerCase()}`)" />
          <Tag
            :value="t(`advertising.moderation_status.${detail.campaign.moderationStatus.toLowerCase()}`)"
            severity="warn"
          />
        </div>
      </section>

      <!-- NG ワード -->
      <section class="mb-6 rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800">
        <h3 class="mb-3 text-base font-semibold text-surface-900 dark:text-surface-50">
          {{ t('advertising.pages.system_admin_moderation_detail.section_ng_words') }}
        </h3>
        <div
          v-if="detail.detectedNgWords.length === 0"
          class="text-sm text-surface-500"
          data-testid="no-ng-words"
        >
          {{ t('advertising.pages.system_admin_moderation_detail.no_ng_words') }}
        </div>
        <div v-else class="flex flex-wrap gap-2" data-testid="ng-words-list">
          <span
            v-for="word in detail.detectedNgWords"
            :key="word"
            class="inline-flex items-center rounded bg-red-100 px-2 py-0.5 text-xs font-semibold text-red-700 dark:bg-red-900/40 dark:text-red-200"
          >
            {{ word }}
          </span>
        </div>
      </section>

      <!-- チャネル本文 -->
      <section class="mb-6 rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800">
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h3 class="text-base font-semibold text-surface-900 dark:text-surface-50">
            {{ t('advertising.pages.system_admin_moderation_detail.section_channels') }}
          </h3>
          <div class="flex items-center gap-2">
            <label class="text-xs text-surface-600 dark:text-surface-300">
              {{ t('advertising.pages.system_admin_moderation_detail.preview_locale') }}:
            </label>
            <Select
              v-model="previewLocale"
              :options="availableLocales"
              data-testid="preview-locale-select"
            />
          </div>
        </div>
        <div v-if="channelsForCurrentLocale.length === 0" class="text-sm text-surface-500">
          {{ t('advertising.pages.advertiser_campaign_detail.no_channels') }}
        </div>
        <div v-else class="space-y-4">
          <article
            v-for="channel in channelsForCurrentLocale"
            :key="channel.id"
            class="rounded border border-surface-200 p-3 dark:border-surface-700"
            :data-testid="`channel-${channel.channelType}`"
          >
            <div class="mb-2 flex items-center gap-2">
              <AdLabelBadge size="sm" />
              <Tag :value="t(`advertising.channel.${channel.channelType.toLowerCase()}`)" />
            </div>
            <!-- eslint-disable vue/no-v-html, vue/html-self-closing — NG ワードハイライトのため意図的に v-html を使用（事前に escapeHtml 済み） -->
            <p
              v-if="channel.subject"
              class="mb-2 text-sm font-semibold text-surface-900 dark:text-surface-50"
              v-html="highlightNgWords(channel.subject)"
            ></p>
            <pre
              class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-200"
              v-html="highlightNgWords(channel.bodyMarkdown)"
            ></pre>
            <!-- eslint-enable vue/no-v-html, vue/html-self-closing -->
          </article>
        </div>
      </section>

      <!-- ターゲットセグメント -->
      <section class="mb-6 rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800">
        <h3 class="mb-3 text-base font-semibold text-surface-900 dark:text-surface-50">
          {{ t('advertising.pages.system_admin_moderation_detail.section_audience') }}
        </h3>
        <div v-if="detail.campaign.audienceSegments.length === 0" class="text-sm text-surface-500">
          {{ t('advertising.pages.advertiser_campaign_detail.no_segments') }}
        </div>
        <ul v-else class="space-y-2 text-sm">
          <li
            v-for="seg in detail.campaign.audienceSegments"
            :key="seg.id"
            class="flex items-center gap-2"
          >
            <Tag
              :value="t(`advertising.inclusion_mode.${seg.inclusionMode.toLowerCase()}`)"
              :severity="seg.inclusionMode === 'INCLUDE' ? 'success' : 'danger'"
            />
            <span class="font-medium">
              {{ t(`advertising.segment_type.${seg.segmentType.toLowerCase()}`) }}:
            </span>
            <code class="text-xs text-surface-600 dark:text-surface-300">
              {{ JSON.stringify(seg.segmentValue) }}
            </code>
          </li>
        </ul>
      </section>

      <!-- アクションフッタ -->
      <footer class="sticky bottom-0 z-10 flex justify-end gap-3 border-t border-surface-200 bg-white/95 py-4 backdrop-blur dark:border-surface-700 dark:bg-surface-800/95">
        <Button
          :label="t('advertising.actions.block')"
          severity="danger"
          icon="pi pi-ban"
          :disabled="approveSubmitting || blockSubmitting"
          @click="openBlockDialog"
        />
        <Button
          :label="t('advertising.actions.approve')"
          severity="success"
          icon="pi pi-check"
          :loading="approveSubmitting"
          :disabled="approveSubmitting || blockSubmitting"
          @click="handleApprove"
        />
      </footer>
    </template>

    <!-- ブロックダイアログ -->
    <Dialog
      v-model:visible="blockDialogOpen"
      modal
      :header="t('advertising.pages.system_admin_moderation.block_dialog_title')"
      :style="{ width: '32rem' }"
    >
      <p class="mb-3 text-sm text-surface-700 dark:text-surface-200">
        {{ t('advertising.pages.system_admin_moderation.block_dialog_message') }}
      </p>
      <label class="mb-1 block text-xs font-medium text-surface-600 dark:text-surface-300">
        {{ t('advertising.pages.system_admin_moderation.block_reason_label') }}
      </label>
      <Textarea
        v-model="blockReason"
        :placeholder="t('advertising.pages.system_admin_moderation.block_reason_placeholder')"
        rows="4"
        class="w-full"
        :maxlength="500"
        data-testid="block-reason-input"
      />
      <template #footer>
        <Button
          :label="t('advertising.actions.cancel')"
          severity="secondary"
          text
          @click="blockDialogOpen = false"
        />
        <Button
          :label="t('advertising.actions.block')"
          severity="danger"
          icon="pi pi-ban"
          :disabled="!blockReason.trim() || blockSubmitting"
          :loading="blockSubmitting"
          @click="handleBlockSubmit"
        />
      </template>
    </Dialog>
  </div>
</template>
