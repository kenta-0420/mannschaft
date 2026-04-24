<script setup lang="ts">
import type {
  ConfirmableNotificationSettings,
  UnconfirmedVisibility,
  UpdateConfirmableNotificationSettingsRequest,
} from '~/types/confirmable'

const props = defineProps<{
  scopeType: 'TEAM' | 'ORGANIZATION'
  scopeId: number
}>()

const { getSettings, updateSettings } = useConfirmableNotificationApi()
const { showError } = useNotification()
const { t } = useI18n()

const loading = ref(false)
const saving = ref(false)

// フォームの値
const firstReminderMinutes = ref<number | null>(null)
const secondReminderMinutes = ref<number | null>(null)
const alertThresholdPercent = ref<number>(50)
const defaultUnconfirmedVisibility = ref<UnconfirmedVisibility>('CREATOR_AND_ADMIN')

/** 未確認者リスト公開範囲のデフォルト選択肢 */
const unconfirmedVisibilityOptions = computed(() => [
  { label: t('confirmable.unconfirmed_visibility.HIDDEN'), value: 'HIDDEN' as const },
  { label: t('confirmable.unconfirmed_visibility.CREATOR_AND_ADMIN'), value: 'CREATOR_AND_ADMIN' as const },
  { label: t('confirmable.unconfirmed_visibility.ALL_MEMBERS'), value: 'ALL_MEMBERS' as const },
])

/** 設定を取得してフォームに反映する */
async function loadSettings() {
  loading.value = true
  try {
    const res = await getSettings(props.scopeType, props.scopeId)
    const settings: ConfirmableNotificationSettings = res.data
    firstReminderMinutes.value = settings.defaultFirstReminderMinutes
    secondReminderMinutes.value = settings.defaultSecondReminderMinutes
    alertThresholdPercent.value = settings.senderAlertThresholdPercent
    defaultUnconfirmedVisibility.value = settings.defaultUnconfirmedVisibility
  } catch (err) {
    console.error('確認通知設定の取得に失敗しました', err)
    showError(t('confirmable.load_settings_error'))
  } finally {
    loading.value = false
  }
}

/** 設定を保存する */
async function saveSettings() {
  saving.value = true
  try {
    const body: UpdateConfirmableNotificationSettingsRequest = {
      defaultFirstReminderMinutes: firstReminderMinutes.value,
      defaultSecondReminderMinutes: secondReminderMinutes.value,
      senderAlertThresholdPercent: alertThresholdPercent.value,
      defaultUnconfirmedVisibility: defaultUnconfirmedVisibility.value,
    }
    await updateSettings(props.scopeType, props.scopeId, body)
    // 保存成功トーストを表示
    const toast = useToast()
    toast.add({ severity: 'success', summary: t('dialog.success'), life: 3000 })
  } catch (err) {
    console.error('確認通知設定の保存に失敗しました', err)
    showError(t('confirmable.save_settings_error'))
  } finally {
    saving.value = false
  }
}

onMounted(() => loadSettings())
</script>

<template>
  <div class="p-4">
    <h3 class="mb-4 text-base font-semibold text-surface-700">
      {{ $t('confirmable.settings') }}
    </h3>

    <div v-if="loading" class="flex justify-center py-8">
      <ProgressSpinner style="width: 40px; height: 40px" />
    </div>

    <div v-else class="flex flex-col gap-4">
      <!-- 1回目リマインド -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          {{ $t('confirmable.first_reminder') }}
          <span class="ml-1 text-xs text-surface-400">（{{ $t('confirmable.reminder_minutes') }}）</span>
        </label>
        <InputNumber
          v-model="firstReminderMinutes"
          :placeholder="$t('label.optional')"
          :min="1"
          :max="10080"
          show-buttons
          class="w-48"
        />
      </div>

      <!-- 2回目リマインド -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          {{ $t('confirmable.second_reminder') }}
          <span class="ml-1 text-xs text-surface-400">（{{ $t('confirmable.reminder_minutes') }}）</span>
        </label>
        <InputNumber
          v-model="secondReminderMinutes"
          :placeholder="$t('label.optional')"
          :min="1"
          :max="10080"
          show-buttons
          class="w-48"
        />
      </div>

      <!-- アラート閾値 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          {{ $t('confirmable.alert_threshold') }}
          <span class="ml-1 text-xs text-surface-400">(%)</span>
        </label>
        <InputNumber
          v-model="alertThresholdPercent"
          :min="1"
          :max="100"
          show-buttons
          suffix="%"
          class="w-48"
        />
      </div>

      <!-- 未確認者リスト公開範囲のデフォルト -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium text-surface-700">
          {{ $t('confirmable.unconfirmed_visibility.label') }}
        </label>
        <Select
          v-model="defaultUnconfirmedVisibility"
          :options="unconfirmedVisibilityOptions"
          option-label="label"
          option-value="value"
          class="w-full max-w-md"
        />
        <p class="text-xs text-surface-400">
          {{ $t('confirmable.unconfirmed_visibility.help') }}
        </p>
      </div>

      <!-- 保存ボタン -->
      <div class="mt-2">
        <Button
          :label="$t('button.save')"
          :loading="saving"
          @click="saveSettings"
        />
      </div>
    </div>
  </div>
</template>
