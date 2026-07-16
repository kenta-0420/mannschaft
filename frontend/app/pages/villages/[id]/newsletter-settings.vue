<script setup lang="ts">
/**
 * F17.1 村機能 — ニュースレター設定（HEADMAN専用）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 * P3 是正: docs/features/F17.1_village_headman_console_and_recruit_categories.md §3.3
 *
 * 構成:
 *   - 上段: 見出し（下記「永続シェル方式」のとおり VillageHeader 自体は親が常駐描画する）
 *   - 下段:
 *       - 週次/月次の有効化トグル
 *       - opt-out 一覧 + 配信履歴
 *
 * 永続シェル方式（SPA）: 【2026-07-16 是正】本コメントは以前
 * 「VillageHeader は付けず独立画面で動作させる」としていたが、これは永続シェル化
 * （親 `pages/villages/[id].vue` が VillageHeader を常駐描画する現行アーキテクチャ）以前の
 * stale な記述だった。実際には親シェルが VillageHeader を描画するため、本ページを開くと
 * VillageHeader は表示される。生きている方針は「9 タブ列（`VillageHeader.vue` の `tabs`）
 * には新タブを追加しない」の一点のみで、本ページはシェルの子として独立画面のまま動作する
 * （村長コンソール `pages/villages/[id]/admin/index.vue` と同じ位置づけ）。
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
const saving = ref(false)

const isHeadman = computed(() => perms.value.isHeadman)

const frequencyOptions = computed(() =>
  [
    { value: 'DAILY' as VillageNewsletterFrequency, key: 'village.newsletter.frequencyOption.DAILY' },
    { value: 'WEEKLY' as VillageNewsletterFrequency, key: 'village.newsletter.frequencyOption.WEEKLY' },
    { value: 'MONTHLY' as VillageNewsletterFrequency, key: 'village.newsletter.frequencyOption.MONTHLY' },
    { value: 'NEVER' as VillageNewsletterFrequency, key: 'village.newsletter.frequencyOption.NEVER' },
  ].map(o => ({ value: o.value, label: t(o.key) })),
)

const selectedFrequency = ref<VillageNewsletterFrequency>('WEEKLY')

async function loadSettings() {
  settingsLoading.value = true
  try {
    settings.value = await villagePhase3Api.getNewsletterSettings(villageId)
    selectedFrequency.value = settings.value.frequency
  }
  catch (error) {
    settings.value = null
    handleApiError(error, t('village.newsletter.loadFailed'))
  }
  finally {
    settingsLoading.value = false
  }
}

async function saveSettings() {
  saving.value = true
  try {
    settings.value = await villagePhase3Api.updateNewsletterSettings(villageId, {
      frequency: selectedFrequency.value,
    })
    success(t('village.newsletter.saveSuccess'))
  }
  catch (error) {
    handleApiError(error, t('village.newsletter.settings'))
  }
  finally {
    saving.value = false
  }
}

async function toggleOptOut() {
  if (!settings.value) return
  try {
    if (settings.value.optedOut) {
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
  }
}

onMounted(() => {
  void loadSettings()
})
</script>

<template>
  <div>
    <template v-if="village">
      <div class="mx-auto max-w-3xl p-4 sm:p-6">
        <div class="flex items-center gap-3 mb-6">
          <NuxtLink :to="`/villages/${village.id}`" class="text-primary-600 hover:underline">
            <i class="pi pi-arrow-left" />
          </NuxtLink>
          <div>
            <h1 class="text-2xl font-bold">
              {{ t('village.newsletter.settings') }}
            </h1>
            <p class="text-sm text-surface-500">
              {{ village.name }}
            </p>
          </div>
        </div>

        <div v-if="!isHeadman" class="rounded-lg border border-warn-300 bg-warn-50 p-6 dark:bg-warn-950">
          <p class="text-sm">
            <i class="pi pi-lock mr-2" />{{ t('village.error.headmanOnly') }}
          </p>
        </div>

        <div v-else-if="settingsLoading" class="text-center py-12 text-surface-500">
          <i class="pi pi-spin pi-spinner text-2xl" />
        </div>

        <div v-else-if="settings" class="flex flex-col gap-6">
          <!-- 配信頻度設定 -->
          <SectionCard :title="t('village.newsletter.frequency')">
            <div class="flex flex-col gap-3">
              <div>
                <Select
                  v-model="selectedFrequency"
                  :options="frequencyOptions"
                  option-value="value"
                  option-label="label"
                  class="w-full sm:w-72"
                />
              </div>
              <div class="flex items-center gap-2">
                <Button
                  :label="t('village.action.save')"
                  icon="pi pi-check"
                  severity="primary"
                  :loading="saving"
                  @click="saveSettings"
                />
              </div>
            </div>
          </SectionCard>

          <!-- 配信状態 + opt-out -->
          <SectionCard :title="t('village.newsletter.title')">
            <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
              <div>
                <div class="text-xs text-surface-500">
                  {{ t('village.newsletter.lastSentAt') }}
                </div>
                <div>{{ settings.lastSentAt ?? '-' }}</div>
              </div>
              <div>
                <div class="text-xs text-surface-500">
                  {{ t('village.newsletter.nextScheduledAt') }}
                </div>
                <div>{{ settings.nextScheduledAt ?? '-' }}</div>
              </div>
            </div>
            <div class="mt-4 flex items-center gap-3 flex-wrap">
              <Badge
                v-if="settings.optedOut"
                :value="t('village.newsletter.optedOut')"
                severity="warn"
              />
              <Button
                :label="settings.optedOut
                  ? t('village.newsletter.optIn')
                  : t('village.newsletter.optOut')"
                :icon="settings.optedOut ? 'pi pi-check' : 'pi pi-ban'"
                :severity="settings.optedOut ? 'success' : 'danger'"
                outlined
                size="small"
                @click="toggleOptOut"
              />
            </div>
          </SectionCard>
        </div>
      </div>
    </template>
  </div>
</template>
