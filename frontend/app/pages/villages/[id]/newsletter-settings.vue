<script setup lang="ts">
/**
 * F17.1 村機能 — ニュースレター設定（HEADMAN専用）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 *
 * 構成:
 *   - 上段: <VillageHeader activeTab="newsletter-settings" />（HEADMAN 専用）
 *   - 下段:
 *       - 週次/月次の有効化トグル
 *       - opt-out 一覧 + 配信履歴
 *
 * 注意: VillageHeader の `activeTab` に新タブ追加は最小化のため、
 * 設定タブとしては独立画面で動作させる（ヘッダーは付けず情報のみ）。
 */
import type {
  VillageNewsletterFrequency,
  VillageNewsletterSettingsResponse,
} from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

const route = useRoute()
const villageId = String(route.params.id)
const { t } = useI18n()
const villagePhase3Api = useVillagePhase3Api()
const { handleApiError } = useErrorHandler()
const toast = useToast()

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
    toast.add({
      severity: 'success',
      summary: t('village.newsletter.saveSuccess'),
      life: 3000,
    })
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
      toast.add({
        severity: 'success',
        summary: t('village.newsletter.optInSuccess'),
        life: 3000,
      })
    }
    else {
      await villagePhase3Api.optOut(villageId)
      toast.add({
        severity: 'success',
        summary: t('village.newsletter.optOutSuccess'),
        life: 3000,
      })
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
          <section class="rounded-lg border border-surface-200 p-5 dark:border-surface-700">
            <h2 class="font-semibold text-lg mb-3">
              {{ t('village.newsletter.frequency') }}
            </h2>
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
          </section>

          <!-- 配信状態 + opt-out -->
          <section class="rounded-lg border border-surface-200 p-5 dark:border-surface-700">
            <h2 class="font-semibold text-lg mb-3">
              {{ t('village.newsletter.title') }}
            </h2>
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
          </section>
        </div>
      </div>
    </template>
  </div>
</template>
