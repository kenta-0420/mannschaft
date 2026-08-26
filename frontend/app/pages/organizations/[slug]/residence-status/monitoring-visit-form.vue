<script setup lang="ts">
// TODO: i18n キーへの移行が必要
import type { ContactResult, MonitoringVisitCreateRequest } from '~/types/residenceStatus'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgSlug = computed(() => String(route.params.slug))

const { createVisit } = useMonitoringVisitApi()

// フォーム状態
const visitedAt = ref<Date>(new Date())
const contactResult = ref<ContactResult>('MET')
const considerationMemo = ref<string>('')
const nextVisitRecommendedAt = ref<Date | null>(null)
const residentRegistryIdInput = ref<string>('')
const dwellingUnitIdInput = ref<string>('')
const committeeIdInput = ref<string>('')

const submitting = ref(false)

// ContactResult の選択肢
const contactResultOptions: { label: string; value: ContactResult }[] = [
  { label: '対面確認', value: 'MET' },
  { label: '応答なし', value: 'NO_RESPONSE' },
  { label: 'ポスト異常', value: 'MAILBOX_ABNORMAL' },
  { label: 'メーター異常', value: 'METER_ABNORMAL' },
  { label: '近隣情報', value: 'NEIGHBOR_INFO' },
  { label: '拒否', value: 'REFUSED' },
  { label: 'その他', value: 'OTHER' },
]

// 送信処理
async function handleSubmit() {
  if (!residentRegistryIdInput.value || !dwellingUnitIdInput.value || !committeeIdInput.value) {
    return
  }

  submitting.value = true
  try {
    const payload: MonitoringVisitCreateRequest = {
      committeeId: Number(committeeIdInput.value),
      residentRegistryId: Number(residentRegistryIdInput.value),
      dwellingUnitId: Number(dwellingUnitIdInput.value),
      subjectUserId: 0, // TODO: 実際のサブジェクトユーザーIDを設定できるよう拡張予定
      visitorUserId: 0, // TODO: 実際の訪問者ユーザーIDを設定できるよう拡張予定
      visitedAt: visitedAt.value ? visitedAt.value.toISOString() : new Date().toISOString(),
      contactResult: contactResult.value,
      considerationMemo: considerationMemo.value || null,
      // BE の nextVisitRecommendedAt は LocalDate。UTC 基準の toISOString() では日付が 1 日ずれる
      nextVisitRecommendedAt: nextVisitRecommendedAt.value
        ? toLocalDateString(nextVisitRecommendedAt.value)
        : null,
    }

    await createVisit(orgSlug.value, payload)
    await navigateTo(`/organizations/${orgSlug.value}/residence-status/dashboard`)
  }
  catch (e) {
    console.error('訪問記録作成エラー:', e)
  }
  finally {
    submitting.value = false
  }
}

function handleCancel() {
  navigateTo(`/organizations/${orgSlug.value}/residence-status/dashboard`)
}
</script>

<template>
  <div class="flex flex-col gap-6 p-6 max-w-2xl mx-auto">
    <!-- TODO: i18n -->
    <div class="flex items-center gap-4">
      <Button
        icon="pi pi-arrow-left"
        text
        severity="secondary"
        @click="handleCancel"
      />
      <h1 class="text-2xl font-bold">
        訪問記録の新規登録
      </h1>
    </div>

    <div class="flex flex-col gap-5 border rounded p-6 bg-white dark:bg-surface-800">
      <!-- 訪問日時 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">訪問日時 <span class="text-red-500">*</span></label>
        <DatePicker
          v-model="visitedAt"
          show-time
          hour-format="24"
          class="w-full"
        />
      </div>

      <!-- 接触結果 -->
      <div class="flex flex-col gap-2">
        <label class="text-sm font-medium">接触結果 <span class="text-red-500">*</span></label>
        <div class="flex flex-wrap gap-2">
          <div
            v-for="option in contactResultOptions"
            :key="option.value"
            class="flex items-center gap-2 cursor-pointer"
          >
            <RadioButton
              v-model="contactResult"
              :input-id="`contact-result-${option.value}`"
              :value="option.value"
            />
            <label
              :for="`contact-result-${option.value}`"
              class="cursor-pointer text-sm"
            >
              {{ option.label }}
            </label>
          </div>
        </div>
      </div>

      <!-- 考慮メモ（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">考慮メモ <span class="text-gray-400 text-xs">（任意・最大2000文字）</span></label>
        <Textarea
          v-model="considerationMemo"
          rows="4"
          :maxlength="2000"
          class="w-full"
          placeholder="訪問時の状況や気になった点を記入してください"
        />
        <div class="text-xs text-gray-400 text-right">
          {{ considerationMemo.length }} / 2000
        </div>
      </div>

      <!-- 次回推奨訪問日（任意） -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">次回推奨訪問日 <span class="text-gray-400 text-xs">（任意）</span></label>
        <DatePicker
          v-model="nextVisitRecommendedAt"
          class="w-full"
          show-button-bar
        />
      </div>

      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <!-- 居住者台帳ID -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">居住者台帳ID <span class="text-red-500">*</span></label>
          <InputText
            v-model="residentRegistryIdInput"
            type="number"
            placeholder="例: 1001"
            class="w-full"
          />
        </div>

        <!-- 住戸ユニットID -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">住戸ユニットID <span class="text-red-500">*</span></label>
          <InputText
            v-model="dwellingUnitIdInput"
            type="number"
            placeholder="例: 201"
            class="w-full"
          />
        </div>

        <!-- 委員会ID -->
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">委員会ID <span class="text-red-500">*</span></label>
          <InputText
            v-model="committeeIdInput"
            type="number"
            placeholder="例: 5"
            class="w-full"
          />
        </div>
      </div>
    </div>

    <!-- フォームアクション -->
    <div class="flex items-center justify-end gap-3">
      <Button
        label="キャンセル"
        severity="secondary"
        outlined
        @click="handleCancel"
      />
      <Button
        label="登録する"
        icon="pi pi-check"
        :loading="submitting"
        :disabled="!residentRegistryIdInput || !dwellingUnitIdInput || !committeeIdInput"
        @click="handleSubmit"
      />
    </div>
  </div>
</template>
