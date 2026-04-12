<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type {
  RecruitmentPenaltySettingResponse,
  RecruitmentUserPenaltyResponse,
  UpsertPenaltySettingRequest,
  LiftPenaltyRequest,
} from '~/types/recruitment'

const route = useRoute()
const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()

const scopeType = computed(() => String(route.params.scopeType))
const scopeId = computed(() => Number(route.params.scopeId))

// ペナルティ設定
const setting = ref<RecruitmentPenaltySettingResponse | null>(null)
const settingLoading = ref(false)
const settingSaving = ref(false)

// フォーム値
const formIsEnabled = ref(false)
const formThresholdCount = ref(3)
const formThresholdPeriodDays = ref(90)
const formPenaltyDurationDays = ref(30)
const formAutoNoShowDetection = ref(false)
const formDisputeAllowedDays = ref(7)

// ペナルティ一覧
const penalties = ref<RecruitmentUserPenaltyResponse[]>([])
const penaltiesLoading = ref(false)
const penaltiesPage = ref(0)
const penaltiesPageSize = ref(20)
const penaltiesTotalElements = ref(0)

// 解除ダイアログ
const liftDialogVisible = ref(false)
const liftingPenalty = ref<RecruitmentUserPenaltyResponse | null>(null)
const liftSubmitting = ref(false)

async function loadSetting() {
  settingLoading.value = true
  try {
    const result = await api.getPenaltySetting(scopeType.value, scopeId.value)
    setting.value = result.data
    syncFormFromSetting(result.data)
  }
  catch {
    // 未設定の場合は null のまま（デフォルト値で新規作成）
  }
  finally {
    settingLoading.value = false
  }
}

function syncFormFromSetting(s: RecruitmentPenaltySettingResponse) {
  formIsEnabled.value = s.isEnabled
  formThresholdCount.value = s.thresholdCount
  formThresholdPeriodDays.value = s.thresholdPeriodDays
  formPenaltyDurationDays.value = s.penaltyDurationDays
  formAutoNoShowDetection.value = s.autoNoShowDetection
  formDisputeAllowedDays.value = s.disputeAllowedDays
}

async function saveSetting() {
  settingSaving.value = true
  try {
    const body: UpsertPenaltySettingRequest = {
      isEnabled: formIsEnabled.value,
      thresholdCount: formThresholdCount.value,
      thresholdPeriodDays: formThresholdPeriodDays.value,
      penaltyDurationDays: formPenaltyDurationDays.value,
      autoNoShowDetection: formAutoNoShowDetection.value,
      disputeAllowedDays: formDisputeAllowedDays.value,
    }
    const result = await api.upsertPenaltySetting(scopeType.value, scopeId.value, body)
    setting.value = result.data
    success(t('recruitment.penalty.saveButton'))
  }
  catch (e) {
    error(String(e))
  }
  finally {
    settingSaving.value = false
  }
}

async function loadPenalties() {
  penaltiesLoading.value = true
  try {
    const result = await api.getScopePenalties(
      scopeType.value,
      scopeId.value,
      penaltiesPage.value,
      penaltiesPageSize.value,
    )
    penalties.value = result.data
    penaltiesTotalElements.value = result.meta.totalElements
  }
  catch (e) {
    error(String(e))
  }
  finally {
    penaltiesLoading.value = false
  }
}

function penaltyStatusLabel(p: RecruitmentUserPenaltyResponse): string {
  if (p.liftedAt) return t('recruitment.penalty.status.lifted')
  if (!p.isActive) return t('recruitment.penalty.status.expired')
  return t('recruitment.penalty.status.active')
}

function penaltyStatusSeverity(p: RecruitmentUserPenaltyResponse): string {
  if (p.liftedAt) return 'secondary'
  if (!p.isActive) return 'warn'
  return 'danger'
}

function openLiftDialog(p: RecruitmentUserPenaltyResponse) {
  liftingPenalty.value = p
  liftDialogVisible.value = true
}

async function submitLift() {
  if (!liftingPenalty.value) return
  liftSubmitting.value = true
  try {
    const body: LiftPenaltyRequest = { liftReason: 'ADMIN_MANUAL' }
    const result = await api.liftPenalty(
      scopeType.value,
      scopeId.value,
      liftingPenalty.value.id,
      body,
    )
    const idx = penalties.value.findIndex(p => p.id === liftingPenalty.value!.id)
    if (idx >= 0) penalties.value[idx] = result.data
    success(t('recruitment.penalty.liftButton'))
    liftDialogVisible.value = false
  }
  catch (e) {
    error(String(e))
  }
  finally {
    liftSubmitting.value = false
  }
}

async function onPenaltiesPageChange(event: { page: number }) {
  penaltiesPage.value = event.page
  await loadPenalties()
}

onMounted(async () => {
  await Promise.all([loadSetting(), loadPenalties()])
})
</script>

<template>
  <div class="container mx-auto max-w-3xl p-4">
    <!-- ペナルティ設定フォーム -->
    <section class="mb-8">
      <PageHeader :title="t('recruitment.penalty.pageTitle')" />

      <div v-if="settingLoading" class="flex justify-center p-8">
        <ProgressSpinner />
      </div>

      <SectionCard v-else class="flex flex-col gap-4">
        <!-- 有効/無効 -->
        <div class="flex items-center gap-3">
          <InputSwitch v-model="formIsEnabled" input-id="isEnabled" />
          <label for="isEnabled" class="text-sm font-medium">
            {{ formIsEnabled ? t('recruitment.penalty.enabled') : t('recruitment.penalty.disabled') }}
          </label>
        </div>

        <div class="grid grid-cols-2 gap-4">
          <!-- 閾値件数 -->
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">{{ t('recruitment.penalty.thresholdCount') }}</label>
            <InputNumber
              v-model="formThresholdCount"
              :min="1"
              :max="99"
              show-buttons
              :disabled="!formIsEnabled"
            />
          </div>

          <!-- 集計期間 -->
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">{{ t('recruitment.penalty.thresholdPeriodDays') }}</label>
            <InputNumber
              v-model="formThresholdPeriodDays"
              :min="1"
              :max="365"
              show-buttons
              :disabled="!formIsEnabled"
            />
          </div>

          <!-- ペナルティ期間 -->
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">{{ t('recruitment.penalty.penaltyDurationDays') }}</label>
            <InputNumber
              v-model="formPenaltyDurationDays"
              :min="1"
              :max="365"
              show-buttons
              :disabled="!formIsEnabled"
            />
          </div>

          <!-- 異議申立期限 -->
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">{{ t('recruitment.penalty.disputeAllowedDays') }}</label>
            <InputNumber
              v-model="formDisputeAllowedDays"
              :min="0"
              :max="90"
              show-buttons
              :disabled="!formIsEnabled"
            />
          </div>
        </div>

        <!-- 自動検出 -->
        <div class="flex items-center gap-3">
          <InputSwitch
            v-model="formAutoNoShowDetection"
            input-id="autoDetection"
            :disabled="!formIsEnabled"
          />
          <label for="autoDetection" class="text-sm font-medium">
            {{ t('recruitment.penalty.autoDetection') }}
          </label>
        </div>

        <div class="flex justify-end">
          <Button
            :label="t('recruitment.penalty.saveButton')"
            icon="pi pi-save"
            :loading="settingSaving"
            @click="saveSetting"
          />
        </div>
      </SectionCard>
    </section>

    <!-- ペナルティ一覧 -->
    <section>
      <h2 class="mb-4 text-xl font-bold">
        {{ t('recruitment.penalty.penaltiesPageTitle') }}
      </h2>

      <div v-if="penaltiesLoading" class="flex justify-center p-8">
        <ProgressSpinner />
      </div>

      <div
        v-else-if="penalties.length === 0"
        class="rounded border border-dashed p-8 text-center text-gray-500"
      >
        {{ t('recruitment.label.noListings') }}
      </div>

      <div v-else class="flex flex-col gap-3">
        <div
          v-for="penalty in penalties"
          :key="penalty.id"
          class="rounded border border-gray-200 p-4"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="flex flex-col gap-1">
              <div class="flex items-center gap-2">
                <span class="text-sm font-medium">user #{{ penalty.userId }}</span>
                <Tag
                  :value="penaltyStatusLabel(penalty)"
                  :severity="penaltyStatusSeverity(penalty)"
                />
              </div>
              <div class="text-sm text-gray-600">
                {{ penalty.penaltyType }}
              </div>
              <div class="text-xs text-gray-400">
                {{ penalty.startedAt }}
                <span v-if="penalty.expiresAt"> ~ {{ penalty.expiresAt }}</span>
              </div>
              <div v-if="penalty.liftReason" class="text-xs text-gray-500">
                {{ penalty.liftReason }}
              </div>
            </div>
            <Button
              v-if="penalty.isActive && !penalty.liftedAt"
              :label="t('recruitment.penalty.liftButton')"
              severity="secondary"
              size="small"
              @click="openLiftDialog(penalty)"
            />
          </div>
        </div>
      </div>

      <Paginator
        v-if="penaltiesTotalElements > penaltiesPageSize"
        :rows="penaltiesPageSize"
        :total-records="penaltiesTotalElements"
        class="mt-4"
        @page="onPenaltiesPageChange"
      />
    </section>

    <!-- ペナルティ解除ダイアログ -->
    <Dialog
      v-model:visible="liftDialogVisible"
      :header="t('recruitment.penalty.liftDialog.title')"
      modal
      :style="{ width: '360px' }"
    >
      <p class="text-sm text-gray-600">
        {{ t('recruitment.penalty.liftDialog.adminManual') }}
      </p>
      <template #footer>
        <Button
          :label="t('recruitment.action.cancel')"
          severity="secondary"
          @click="liftDialogVisible = false"
        />
        <Button
          :label="t('recruitment.penalty.liftButton')"
          :loading="liftSubmitting"
          severity="danger"
          @click="submitLift"
        />
      </template>
    </Dialog>
  </div>
</template>
