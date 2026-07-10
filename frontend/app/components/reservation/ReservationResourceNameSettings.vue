<script setup lang="ts">
/**
 * 予約対象の呼称設定UI（F03.4.5 §5.1・ADMIN/DEPUTY_ADMIN限定・編集）
 *
 * ②予約対象セクションの先頭（LineManager上部）に配置する（§5.1確定）。
 * プリセット6種（DEFAULT/STAFF/SEAT/COURT/BED/LANE）＋自由入力（CUSTOM）を Select で切り替え、
 * CUSTOM 選択時のみ自由入力欄（30文字以内）を表示する。
 *
 * - CUSTOM かつ自由入力が空のまま保存しようとした場合は 400 を待たず FE で止める（custom_required）。
 * - CUSTOM 以外では resourceNameCustom を送らない（BE が NULL に正規化・§5.1）。
 * - 非ADMIN/DEPUTY_ADMIN（disabled=true）は Select・入力とも disabled（表示のみ・最終ゲートはBE）。
 */
import type { ReservationResourceNameTypeCode } from '~/types/reservation'

const props = defineProps<{
  teamId: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 保存成功時（更新後の resourceNameType/resourceNameCustom）。親がキャッシュ・他コンポーネントを再読込する。 */
  changed: [resourceNameType: ReservationResourceNameTypeCode, resourceNameCustom: string | null]
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const loading = ref(false)
const saving = ref(false)

const resourceNameType = ref<ReservationResourceNameTypeCode>('DEFAULT')
const resourceNameCustom = ref<string>('')
/** カスタム必須エラー（AC-N3: CUSTOM かつ空のまま保存しようとした場合）。 */
const customRequiredError = ref(false)

const PRESET_TYPES: ReservationResourceNameTypeCode[] = ['DEFAULT', 'STAFF', 'SEAT', 'COURT', 'BED', 'LANE']

const typeOptions = computed(() => [
  ...PRESET_TYPES.map(value => ({ label: t(`reservation.resource_name.${value}`), value })),
  { label: t('reservation.resource_name.custom_label'), value: 'CUSTOM' as const },
])

async function loadSettings() {
  loading.value = true
  try {
    const res = await reservationApi.getReservationSettings(props.teamId)
    resourceNameType.value = res.data.resourceNameType ?? 'DEFAULT'
    resourceNameCustom.value = res.data.resourceNameCustom ?? ''
  }
  catch {
    // 取得失敗: 既定値（DEFAULT）のまま表示（§5.1 のフォールバックと同じ思想）
    resourceNameType.value = 'DEFAULT'
    resourceNameCustom.value = ''
  }
  finally {
    loading.value = false
  }
}

watch(resourceNameType, () => {
  customRequiredError.value = false
})

async function save() {
  if (resourceNameType.value === 'CUSTOM' && !resourceNameCustom.value.trim()) {
    customRequiredError.value = true
    return
  }
  customRequiredError.value = false
  saving.value = true
  try {
    const body: Parameters<typeof reservationApi.updateReservationSettings>[1] = {
      resourceNameType: resourceNameType.value,
    }
    // CUSTOM 以外では resourceNameCustom を送らない（BE が NULL に正規化する・§5.1）
    if (resourceNameType.value === 'CUSTOM') {
      body.resourceNameCustom = resourceNameCustom.value.trim()
    }
    const res = await reservationApi.updateReservationSettings(props.teamId, body)
    resourceNameType.value = res.data.resourceNameType ?? 'DEFAULT'
    resourceNameCustom.value = res.data.resourceNameCustom ?? ''
    notification.success(t('reservation.settings.policy.save_success'))
    emit('changed', resourceNameType.value, resourceNameCustom.value || null)
  }
  catch (error) {
    handleApiError(error)
  }
  finally {
    saving.value = false
  }
}

onMounted(loadSettings)
</script>

<template>
  <div class="space-y-3 rounded-lg border border-surface-200 p-4 dark:border-surface-700" data-testid="resource-name-settings">
    <div v-if="loading" class="space-y-2">
      <Skeleton height="2.5rem" width="100%" />
    </div>
    <template v-else>
      <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
        {{ t('reservation.resource_name.setting_title') }}
      </label>
      <div class="flex flex-wrap items-center gap-2">
        <Select
          v-model="resourceNameType"
          :options="typeOptions"
          option-label="label"
          option-value="value"
          :disabled="disabled || saving"
          class="w-full sm:w-64"
          data-testid="resource-name-type-select"
        />
        <template v-if="resourceNameType === 'CUSTOM'">
          <InputText
            v-model="resourceNameCustom"
            :maxlength="30"
            :disabled="disabled || saving"
            :placeholder="t('reservation.resource_name.custom_label')"
            class="w-full sm:w-56"
            data-testid="resource-name-custom-input"
          />
        </template>
        <Button
          :label="t('reservation.button.save')"
          size="small"
          :disabled="disabled || saving"
          :loading="saving"
          data-testid="resource-name-save"
          @click="save"
        />
      </div>
      <p v-if="customRequiredError" class="text-xs text-red-500" data-testid="resource-name-custom-required">
        {{ t('reservation.resource_name.custom_required') }}
      </p>
    </template>
  </div>
</template>
