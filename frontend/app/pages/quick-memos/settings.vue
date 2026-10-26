<script setup lang="ts">
import type { UserQuickMemoSettingsResponse, UpdateSettingsRequest } from '~/types/quickMemo'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const settingsApi = useQuickMemoSettings()

const settings = ref<UserQuickMemoSettingsResponse | null>(null)
const loading = ref(false)
const saving = ref(false)

// フォーム
const form = ref({
  reminderEnabled: false,
  slots: [
    { enabled: false, days: 3, time: '09:00' },
    { enabled: false, days: 5, time: '09:00' },
    { enabled: false, days: 10, time: '09:00' },
  ],
})

// 適用範囲ダイアログ
const showApplyDialog = ref(false)
const applyTo = ref<'NEW_ONLY' | 'UNSENT' | 'ALL'>('NEW_ONLY')

const applyOptions = [
  { value: 'NEW_ONLY', label: t('quick_memo.settings.apply_new_only') },
  { value: 'UNSENT', label: t('quick_memo.settings.apply_unsent') },
  { value: 'ALL', label: t('quick_memo.settings.apply_all') },
]

const timeOptions = computed(() => {
  const opts: { label: string; value: string }[] = []
  for (let h = 0; h < 24; h++) {
    opts.push(
      { label: `${String(h).padStart(2, '0')}:00`, value: `${String(h).padStart(2, '0')}:00` },
      { label: `${String(h).padStart(2, '0')}:30`, value: `${String(h).padStart(2, '0')}:30` },
    )
  }
  return opts
})

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  try {
    const res = await settingsApi.getSettings()
    settings.value = res.data
    applySettingsToForm(res.data)
  } catch {
    notification.error(t('quick_memo.settings.load_error'))
  } finally {
    loading.value = false
  }
}

function applySettingsToForm(s: UserQuickMemoSettingsResponse) {
  form.value.reminderEnabled = s.reminderEnabled
  form.value.slots[0]!.enabled = s.defaultOffset1Days !== null
  form.value.slots[0]!.days = s.defaultOffset1Days ?? 3
  form.value.slots[0]!.time = s.defaultTime1 ?? '09:00'
  form.value.slots[1]!.enabled = s.defaultOffset2Days !== null
  form.value.slots[1]!.days = s.defaultOffset2Days ?? 5
  form.value.slots[1]!.time = s.defaultTime2 ?? '09:00'
  form.value.slots[2]!.enabled = s.defaultOffset3Days !== null
  form.value.slots[2]!.days = s.defaultOffset3Days ?? 10
  form.value.slots[2]!.time = s.defaultTime3 ?? '09:00'
}

function openApplyDialog() {
  showApplyDialog.value = true
}

async function confirmSave() {
  showApplyDialog.value = false
  saving.value = true
  try {
    const body: UpdateSettingsRequest = {
      reminderEnabled: form.value.reminderEnabled,
      defaultOffset1Days: form.value.slots[0]!.enabled ? form.value.slots[0]!.days : null,
      defaultTime1: form.value.slots[0]!.enabled ? form.value.slots[0]!.time : null,
      defaultOffset2Days: form.value.slots[1]!.enabled ? form.value.slots[1]!.days : null,
      defaultTime2: form.value.slots[1]!.enabled ? form.value.slots[1]!.time : null,
      defaultOffset3Days: form.value.slots[2]!.enabled ? form.value.slots[2]!.days : null,
      defaultTime3: form.value.slots[2]!.enabled ? form.value.slots[2]!.time : null,
    }
    await settingsApi.updateSettings(body, applyTo.value)
    notification.success(t('quick_memo.settings.saved'))
  } catch {
    notification.error(t('quick_memo.settings.save_error'))
  } finally {
    saving.value = false
  }
}

const previewText = computed(() => {
  const enabled = form.value.slots
    .map((s, i) => ({ ...s, label: t(`quick_memo.reminder.slot${i + 1}`) }))
    .filter((s) => s.enabled && form.value.reminderEnabled)
  if (enabled.length === 0) return t('quick_memo.settings.preview_none')
  return t('quick_memo.settings.preview', {
    slots: enabled.map((s) => `${s.days}${t('quick_memo.settings.days_after')} ${s.time}`).join('・'),
  })
})
</script>

<template>
  <div class="mx-auto max-w-xl px-4 py-6">
    <div class="mb-6 flex items-center gap-3">
      <Button
        icon="pi pi-arrow-left"
        rounded
        text
        @click="$router.push('/quick-memos')"
      />
      <h1 class="text-xl font-bold">{{ t('quick_memo.settings.title') }}</h1>
    </div>

    <div v-if="loading" class="space-y-4">
      <Skeleton height="60px" />
      <Skeleton height="120px" />
    </div>

    <div v-else class="space-y-6">
      <!-- リマインド有効化トグル -->
      <div class="flex items-center justify-between rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800">
        <div>
          <p class="font-medium">{{ t('quick_memo.settings.enable_reminder') }}</p>
          <p class="text-sm text-surface-500">{{ t('quick_memo.settings.enable_reminder_desc') }}</p>
        </div>
        <ToggleSwitch v-model="form.reminderEnabled" />
      </div>

      <!-- 3スロット -->
      <div v-if="form.reminderEnabled" class="space-y-3">
        <div
          v-for="(slot, i) in form.slots"
          :key="i"
          class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
        >
          <div class="flex items-center gap-3">
            <Checkbox v-model="slot.enabled" :binary="true" />
            <span class="text-sm font-medium">{{ t(`quick_memo.reminder.slot${i + 1}`) }}</span>
          </div>
          <div v-if="slot.enabled" class="mt-3 flex items-center gap-2">
            <InputNumber
              v-model="slot.days"
              :min="1"
              :max="90"
              class="w-20"
              :suffix="t('quick_memo.settings.days_suffix')"
            />
            <span class="text-sm text-surface-500">{{ t('quick_memo.settings.days_after') }}</span>
            <Select
              v-model="slot.time"
              :options="timeOptions"
              option-label="label"
              option-value="value"
              class="w-28"
            />
          </div>
        </div>
      </div>

      <!-- プレビュー -->
      <div class="rounded-lg bg-surface-100 p-3 text-sm text-surface-600 dark:bg-surface-700 dark:text-surface-300">
        {{ previewText }}
      </div>

      <!-- 音声同意管理 -->
      <QuickMemoVoiceInput class="hidden" />

      <!-- 保存 -->
      <Button
        :label="t('button.save')"
        :loading="saving"
        class="w-full"
        @click="openApplyDialog"
      />
    </div>

    <!-- 適用範囲ダイアログ -->
    <Dialog
      v-model:visible="showApplyDialog"
      :header="t('quick_memo.settings.apply_dialog_title')"
      modal
      class="w-full max-w-md"
    >
      <div class="space-y-3">
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ t('quick_memo.settings.apply_dialog_body') }}
        </p>
        <div class="space-y-2">
          <div
            v-for="opt in applyOptions"
            :key="opt.value"
            class="flex cursor-pointer items-center gap-2"
            @click="applyTo = opt.value as 'NEW_ONLY' | 'UNSENT' | 'ALL'"
          >
            <RadioButton v-model="applyTo" :value="opt.value" />
            <label class="cursor-pointer text-sm">{{ opt.label }}</label>
          </div>
        </div>
      </div>
      <template #footer>
        <Button :label="t('button.cancel')" severity="secondary" @click="showApplyDialog = false" />
        <Button :label="t('button.confirm')" @click="confirmSave" />
      </template>
    </Dialog>
  </div>
</template>
