<script setup lang="ts">
import type { ProxyInputConsent, ProxyInputSource } from '~/types/proxy-input'

const { t } = useI18n()

const { isPinned, pinnedSubjectUserId, pinnedConsentId, pin, unpin } = useProxyDesk()
const proxyApi = useProxyInputApi()

/** 同意書一覧 */
const consents = ref<ProxyInputConsent[]>([])

/** 選択中の同意書 */
const selectedConsent = ref<ProxyInputConsent | null>(null)

/** 選択中の入力元 */
const selectedInputSource = ref<ProxyInputSource>('PAPER_FORM')

/** 原本保管場所 */
const originalStorageLocation = ref('')

/** ローディング中フラグ */
const loading = ref(false)

/** 入力元選択肢 */
const inputSourceOptions = computed(() => [
  { label: t('proxy.desk.inputSource.paper_form'), value: 'PAPER_FORM' as ProxyInputSource },
  { label: t('proxy.desk.inputSource.phone_interview'), value: 'PHONE_INTERVIEW' as ProxyInputSource },
  { label: t('proxy.desk.inputSource.in_person'), value: 'IN_PERSON' as ProxyInputSource },
])

/** ピン留めボタンの活性条件: 同意書が選択済みかつ未ピン留め */
const canPin = computed(() => selectedConsent.value !== null && !isPinned.value)

/** 選択した同意書のIDを返す（ハイライト判定用） */
function isSelected(consent: ProxyInputConsent): boolean {
  return selectedConsent.value?.id === consent.id
}

/** 同意書を選択する */
function handleSelectConsent(consent: ProxyInputConsent) {
  selectedConsent.value = consent
}

/** 選択した同意書でピン留めする */
function handlePin() {
  if (!selectedConsent.value) return
  pin(
    selectedConsent.value.subjectUserId,
    selectedConsent.value.id,
    selectedInputSource.value,
    originalStorageLocation.value,
  )
}

/** ピン留めを解除する */
function handleUnpin() {
  unpin()
  selectedConsent.value = null
  originalStorageLocation.value = ''
}

onMounted(async () => {
  loading.value = true
  try {
    consents.value = await proxyApi.getActiveConsents()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="flex flex-col gap-4 rounded-xl border border-surface-200 bg-white p-4 shadow-sm dark:border-surface-700 dark:bg-surface-900">
    <!-- ヘッダー: タイトル + ステータスバッジ -->
    <div class="flex items-center justify-between">
      <h2 class="text-base font-bold text-surface-800 dark:text-surface-100">
        {{ t('proxy.title') }}
      </h2>
      <Tag
        :value="isPinned ? t('proxy.desk.active') : t('proxy.desk.inactive')"
        :severity="isPinned ? 'success' : 'secondary'"
      />
    </div>

    <!-- 稼働中バナー -->
    <div
      v-if="isPinned"
      class="flex items-center gap-2 rounded-lg bg-orange-50 px-4 py-3 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300"
    >
      <i class="pi pi-exclamation-triangle text-lg" />
      <span class="text-sm font-semibold">{{ t('proxy.desk.active') }}</span>
      <span class="ml-1 text-xs opacity-80">
        ({{ t('proxy.consent.subjectUserId') }}: {{ pinnedSubjectUserId }} /
        {{ t('proxy.consent.title') }} #{{ pinnedConsentId }})
      </span>
    </div>

    <!-- 入力元選択 -->
    <div class="flex flex-col gap-1">
      <label class="text-xs font-medium text-surface-600 dark:text-surface-400">
        {{ t('proxy.desk.inputSource.label') }}
      </label>
      <Dropdown
        v-model="selectedInputSource"
        :options="inputSourceOptions"
        option-label="label"
        option-value="value"
        :disabled="isPinned"
        class="w-full"
      />
    </div>

    <!-- 原本保管場所 -->
    <div class="flex flex-col gap-1">
      <label class="text-xs font-medium text-surface-600 dark:text-surface-400">
        {{ t('proxy.desk.originalStorage.label') }}
      </label>
      <InputText
        v-model="originalStorageLocation"
        :placeholder="t('proxy.desk.originalStorage.placeholder')"
        :disabled="isPinned"
        class="w-full"
      />
    </div>

    <!-- 同意書一覧 -->
    <div class="flex flex-col gap-2">
      <p class="text-xs font-medium text-surface-600 dark:text-surface-400">
        {{ t('proxy.desk.pinConsent') }}
      </p>

      <!-- ローディング -->
      <div v-if="loading" class="flex justify-center py-4">
        <i class="pi pi-spin pi-spinner text-xl text-primary" />
      </div>

      <!-- 同意書なし -->
      <div
        v-else-if="consents.length === 0"
        class="rounded-lg bg-surface-50 py-6 text-center text-sm text-surface-400 dark:bg-surface-800 dark:text-surface-500"
      >
        {{ t('proxy.consent.noActive') }}
      </div>

      <!-- 同意書リスト -->
      <div v-else class="flex max-h-64 flex-col gap-2 overflow-y-auto">
        <ProxyConsentListItem
          v-for="consent in consents"
          :key="consent.id"
          :consent="consent"
          :selected="isSelected(consent)"
          @select="handleSelectConsent"
        />
      </div>
    </div>

    <!-- ピン留めボタン -->
    <Button
      v-if="!isPinned"
      :label="t('proxy.desk.pinSubject')"
      icon="pi pi-map-marker"
      :disabled="!canPin"
      severity="primary"
      class="w-full"
      @click="handlePin"
    />

    <!-- ピン留め解除ボタン -->
    <Button
      v-if="isPinned"
      :label="t('proxy.desk.unpin')"
      icon="pi pi-times-circle"
      severity="danger"
      outlined
      class="w-full"
      @click="handleUnpin"
    />
  </div>
</template>
