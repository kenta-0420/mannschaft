<script setup lang="ts">
/**
 * F17.1 村機能 — ニュースレター設定（`/villages/[id]/newsletter-settings`）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 * P3 是正: docs/features/F17.1_village_headman_console_and_recruit_categories.md §3.3 / §12.1
 * 課題D（契約不一致 19 件目）マスター御裁可（2026-07-16）:
 *   BE の実契約（`VillageNewsletterController` / `NewsletterSettingsResponse`）を正として FE を寄せる。
 *
 * # 機能の実体（BE を正）
 *  「頻度を 1 つ選ぶ」ではなく **WEEKLY / MONTHLY をそれぞれオン/オフ**する。
 *  GET `/newsletter` は `{villageId, settings: WEEKLY/MONTHLY の 0〜2 件, optedOut}` を返す。
 *  各トグルは settings 配列から導出し、操作すると該当頻度を PUT `{frequency, isEnabled}` で upsert する。
 *
 * # 2 区画に明確分離（マスター御裁可）
 *  - 区画A「村の配信設定」: WEEKLY / MONTHLY のトグル。**村長 HEADMAN・長老 ELDER のみ編集可**、
 *    それ以外の村人には読み取り専用（disabled）で現状を見せる（丸ごと隠さない）。
 *  - 区画B「あなたの受信設定」: 個人 opt-out トグル（村人全員操作可）。村の配信設定とは別物。
 *
 * # 表示の割り切り（御裁可）
 *  - `nextScheduledAt` は常に null のため表示しない。`lastSentAt` は表示してよい。
 *  - ⚠️ 配信は未実装（`deliverToUser` が log プレースホルダ）。使い方モーダルに「配信機能は準備中」を明記。
 *
 * # 永続シェル方式（SPA）
 *  村データ・権限は親 `pages/villages/[id].vue` が解決済み。`useVillageContext()` で inject
 *  するのみで村は再フェッチしない。
 */
import type {
  VillageNewsletterFrequency,
  VillageNewsletterSettingsResponse,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = String(route.params.id)
const { t } = useI18n()
const villagePhase3Api = useVillagePhase3Api()
const { handleApiError } = useErrorHandler()
const { success } = useNotification()

// 村本体・権限は親シェルから inject（再フェッチしない）
const { village, perms } = useVillageContext()

// =====================================================================
// State
// =====================================================================

const settings = ref<VillageNewsletterSettingsResponse | null>(null)
const settingsLoading = ref(false)

/** 各頻度の保存中フラグ（トグルを一時 disabled にする）。 */
const savingFrequency = ref<VillageNewsletterFrequency | null>(null)
/** 受信設定（opt-out）の保存中フラグ。 */
const savingReceive = ref(false)

/**
 * 村の配信設定を変更できるか（村長 HEADMAN または長老 ELDER）。
 *
 * BE `VillageNewsletterService#requireHeadmanOrElder` は HEADMAN / ELDER を許可する。
 * `perms.isAdmin` は「HEADMAN or ELDER」と一致する（useVillageContext）。
 */
const canManage = computed(() => perms.value.isAdmin)

// --- トグルの表示値（controlled: サーバー状態が確定するまで書き換えない） ---

/** 区画A: 週次トグルの現在値（settings 配列から導出）。 */
const weeklyEnabled = ref(false)
/** 区画A: 月次トグルの現在値（settings 配列から導出）。 */
const monthlyEnabled = ref(false)
/** 区画B: 「このニュースレターを受信する」トグル（= !optedOut）。 */
const receiveOn = ref(true)

/** 各頻度の最終配信日時（表示用・未配信は null）。 */
const lastSentAt = reactive<Record<VillageNewsletterFrequency, string | null>>({
  WEEKLY: null,
  MONTHLY: null,
})

/** settings レスポンスからトグル・表示値を同期する。 */
function syncFromSettings() {
  const s = settings.value
  const weekly = s?.settings.find(x => x.frequency === 'WEEKLY')
  const monthly = s?.settings.find(x => x.frequency === 'MONTHLY')
  weeklyEnabled.value = !!weekly?.isEnabled
  monthlyEnabled.value = !!monthly?.isEnabled
  lastSentAt.WEEKLY = weekly?.lastSentAt ?? null
  lastSentAt.MONTHLY = monthly?.lastSentAt ?? null
  receiveOn.value = !(s?.optedOut ?? false)
}

async function loadSettings() {
  settingsLoading.value = true
  try {
    settings.value = await villagePhase3Api.getNewsletterSettings(villageId)
    syncFromSettings()
  }
  catch (error) {
    settings.value = null
    handleApiError(error, t('village.newsletter.loadFailed'))
  }
  finally {
    settingsLoading.value = false
  }
}

/**
 * 区画A: 指定頻度のトグル操作 → PUT `{frequency, isEnabled}`。
 *
 * controlled トグルなので `weeklyEnabled` / `monthlyEnabled` は成功するまで書き換えない。
 * 失敗時は loadSettings でサーバー状態へ戻す（対処療法で握りつぶさない）。
 */
async function onToggleFrequency(frequency: VillageNewsletterFrequency, next: boolean) {
  if (!canManage.value || savingFrequency.value) return
  savingFrequency.value = frequency
  try {
    await villagePhase3Api.updateNewsletterSettings(villageId, { frequency, isEnabled: next })
    success(t('village.newsletter.saveSuccess'))
    await loadSettings()
  }
  catch (error) {
    handleApiError(error, t('village.newsletter.settings'))
    await loadSettings()
  }
  finally {
    savingFrequency.value = null
  }
}

/**
 * 区画B: 受信トグル操作 → opt-in / opt-out（204 No Content）。
 *
 * `next=true`（受信する）＝ opt-in、`next=false`（受信しない）＝ opt-out。
 */
async function onToggleReceive(next: boolean) {
  if (savingReceive.value) return
  savingReceive.value = true
  try {
    if (next) {
      await villagePhase3Api.optIn(villageId)
      success(t('village.newsletter.optInSuccess'))
    }
    else {
      await villagePhase3Api.optOut(villageId)
      success(t('village.newsletter.optOutSuccess'))
    }
    await loadSettings()
  }
  catch (error) {
    handleApiError(error, t('village.newsletter.settings'))
    await loadSettings()
  }
  finally {
    savingReceive.value = false
  }
}

onMounted(() => {
  void loadSettings()
})

// 親シェルは村取得をクライアントで行うため、村確定が本ページのマウント後になりうる。
watch(village, (v) => {
  if (v && !settings.value) void loadSettings()
})

// =====================================================================
// 使い方モーダル
// =====================================================================

const showGuide = ref(false)
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <PageHeader
      :title="t('village.newsletter.settings')"
      size="sm"
      help
      :back-to="village ? `/villages/${village.id}/admin` : `/villages/${villageId}/admin`"
      @help="showGuide = true"
    >
      <template v-if="village" #actions>
        <span class="text-sm text-surface-500">{{ village.name }}</span>
      </template>
    </PageHeader>

    <div v-if="settingsLoading" class="py-12 text-center text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" aria-hidden="true" />
    </div>

    <div v-else-if="settings" class="flex flex-col gap-6">
      <!-- 区画A: 村の配信設定（HEADMAN / ELDER 編集可・他は読み取り専用） -->
      <SectionCard :title="t('village.newsletter.villageSection.title')">
        <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
          {{ t('village.newsletter.villageSection.subtitle') }}
        </p>

        <p v-if="!canManage" class="mb-4 flex items-center gap-2 text-xs text-surface-500">
          <i class="pi pi-lock" aria-hidden="true" />{{ t('village.newsletter.manageOnlyHint') }}
        </p>

        <div class="flex flex-col divide-y divide-surface-200 dark:divide-surface-700">
          <!-- 週次 -->
          <div class="flex items-center justify-between gap-4 py-3">
            <div>
              <div class="font-medium text-surface-800 dark:text-surface-100">
                {{ t('village.newsletter.weekly.label') }}
              </div>
              <div class="text-xs text-surface-500">
                {{ t('village.newsletter.weekly.timing') }}
              </div>
              <div v-if="lastSentAt.WEEKLY" class="mt-0.5 text-xs text-surface-400">
                {{ t('village.newsletter.lastSentAt') }}: {{ lastSentAt.WEEKLY }}
              </div>
            </div>
            <ToggleSwitch
              :model-value="weeklyEnabled"
              :disabled="!canManage || savingFrequency !== null"
              data-testid="newsletter-toggle-WEEKLY"
              :aria-label="t('village.newsletter.weekly.label')"
              @update:model-value="(v: boolean) => onToggleFrequency('WEEKLY', v)"
            />
          </div>

          <!-- 月次 -->
          <div class="flex items-center justify-between gap-4 py-3">
            <div>
              <div class="font-medium text-surface-800 dark:text-surface-100">
                {{ t('village.newsletter.monthly.label') }}
              </div>
              <div class="text-xs text-surface-500">
                {{ t('village.newsletter.monthly.timing') }}
              </div>
              <div v-if="lastSentAt.MONTHLY" class="mt-0.5 text-xs text-surface-400">
                {{ t('village.newsletter.lastSentAt') }}: {{ lastSentAt.MONTHLY }}
              </div>
            </div>
            <ToggleSwitch
              :model-value="monthlyEnabled"
              :disabled="!canManage || savingFrequency !== null"
              data-testid="newsletter-toggle-MONTHLY"
              :aria-label="t('village.newsletter.monthly.label')"
              @update:model-value="(v: boolean) => onToggleFrequency('MONTHLY', v)"
            />
          </div>
        </div>

        <!-- 配信未実装の正直な注記（症状を隠さない） -->
        <Message severity="info" :closable="false" class="mt-4">
          {{ t('village.newsletter.comingSoonNote') }}
        </Message>
      </SectionCard>

      <!-- 区画B: あなたの受信設定（村人全員操作可） -->
      <SectionCard :title="t('village.newsletter.personalSection.title')">
        <div class="flex items-center justify-between gap-4">
          <div>
            <div class="font-medium text-surface-800 dark:text-surface-100">
              {{ t('village.newsletter.personalSection.toggleLabel') }}
            </div>
            <div class="text-xs text-surface-500">
              {{ receiveOn
                ? t('village.newsletter.personalSection.receivingHint')
                : t('village.newsletter.personalSection.stoppedHint') }}
            </div>
          </div>
          <ToggleSwitch
            :model-value="receiveOn"
            :disabled="savingReceive"
            data-testid="newsletter-receive-toggle"
            :aria-label="t('village.newsletter.personalSection.toggleLabel')"
            @update:model-value="onToggleReceive"
          />
        </div>
      </SectionCard>
    </div>

    <!-- 使い方モーダル -->
    <VillageNewsletterGuideModal v-model:visible="showGuide" />
  </div>
</template>
