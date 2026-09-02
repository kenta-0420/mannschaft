<script setup lang="ts">
/**
 * F08.4 §9.2 発行者設定画面。
 *
 * scopeId が確定するまで API を叩かない（空文字を Long scopeId へ送ると 400 になるため）。
 * 更新は差分更新（PATCH）。触っていない項目は送らず、明示クリアしたい項目だけ空文字で送る。
 */
import type { ReceiptIssuerSettings } from '~/types/receipt'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { success, error: showError } = useNotification()
const { getSettings, updateSettings, uploadLogo, deleteLogo } = useReceiptApi()

const scopeStore = useScopeStore()
const scopeId = computed(() => scopeStore.current.id ?? '')
const scopeType = computed((): 'TEAM' | 'ORGANIZATION' =>
  scopeStore.current.type === 'organization' ? 'ORGANIZATION' : 'TEAM',
)

const SEAL_VARIANTS = ['LAST_NAME', 'FULL_NAME', 'FIRST_NAME'] as const
const sealVariantOptions = SEAL_VARIANTS.map((v) => ({
  label: t(`receipt.settings.sealVariant.${v}`),
  value: v,
}))
const fiscalMonthOptions = Array.from({ length: 12 }, (_, i) => ({ label: String(i + 1), value: i + 1 }))

interface SettingsForm {
  issuerName: string
  postalCode: string
  address: string
  phone: string
  isQualifiedInvoicer: boolean
  invoiceRegistrationNumber: string
  defaultSealVariant: string | null
  receiptNoteTemplate: string
  receiptNumberPrefix: string
  fiscalYearStartMonth: number
  autoResetNumber: boolean
  customFooter: string
}

function emptyForm(): SettingsForm {
  return {
    issuerName: '',
    postalCode: '',
    address: '',
    phone: '',
    isQualifiedInvoicer: false,
    invoiceRegistrationNumber: '',
    defaultSealVariant: null,
    receiptNoteTemplate: '',
    receiptNumberPrefix: '',
    fiscalYearStartMonth: 4,
    autoResetNumber: true,
    customFooter: '',
  }
}

const form = ref<SettingsForm>(emptyForm())
const loading = ref(true)
const saving = ref(false)
const isFirstTime = ref(true)
const nextReceiptNumber = ref<number | null>(null)
const dirty = ref(false)

// バリデーションエラー（入力欄直下に表示。§9.2）
const issuerNameError = ref<string | null>(null)
const registrationNumberError = ref<string | null>(null)

const REGISTRATION_NUMBER_PATTERN = /^T\d{13}$/

watch(form, () => { dirty.value = true }, { deep: true })

function applyResponse(data: ReceiptIssuerSettings) {
  form.value = {
    issuerName: data.issuerName ?? '',
    postalCode: data.postalCode ?? '',
    address: data.address ?? '',
    phone: data.phone ?? '',
    isQualifiedInvoicer: data.isQualifiedInvoicer ?? false,
    invoiceRegistrationNumber: data.invoiceRegistrationNumber ?? '',
    defaultSealVariant: data.defaultSealVariant ?? null,
    receiptNoteTemplate: data.receiptNoteTemplate ?? '',
    receiptNumberPrefix: data.receiptNumberPrefix ?? '',
    fiscalYearStartMonth: data.fiscalYearStartMonth ?? 4,
    autoResetNumber: data.autoResetNumber ?? true,
    customFooter: data.customFooter ?? '',
  }
  logoUrl.value = data.logoUrl ?? null
  nextReceiptNumber.value = data.nextReceiptNumber ?? 1
  isFirstTime.value = false
  dirty.value = false
}

/** BE の構造化エラーから errorCode を取り出す（AC-31 の RECEIPT_001 判定用）。 */
function extractErrorCode(err: unknown): string | null {
  const data = (err as { data?: { errorCode?: string } }).data
  return data?.errorCode ?? null
}

function extractStatus(err: unknown): number | undefined {
  const e = err as { response?: { status?: number }; statusCode?: number; status?: number }
  return e?.response?.status ?? e?.statusCode ?? e?.status
}

async function load() {
  if (!scopeId.value) return
  loading.value = true
  try {
    const res = await getSettings(scopeType.value, scopeId.value)
    applyResponse(res.data)
  } catch (err) {
    // 未作成スコープ（RECEIPT_001）はエラーではなく初期値表示（D-4・AC-2・AC-31）
    if (extractErrorCode(err) === 'RECEIPT_001') {
      form.value = emptyForm()
      logoUrl.value = null
      nextReceiptNumber.value = null
      isFirstTime.value = true
      dirty.value = false
    } else if (extractStatus(err) === 403) {
      showError(t('receipt.settings.toast.forbidden'))
    } else {
      showError(t('receipt.settings.toast.loadFailed'))
    }
  } finally {
    loading.value = false
  }
}

// scopeId が truthy になるのを待ってから初回発火する（空文字を Long へ送ると 400 になるため）
watch(scopeId, (v) => { if (v) load() }, { immediate: true })

function validate(): boolean {
  issuerNameError.value = null
  registrationNumberError.value = null
  let ok = true

  if (!form.value.issuerName.trim()) {
    issuerNameError.value = t('receipt.settings.validation.issuerNameRequired')
    ok = false
  }

  if (form.value.isQualifiedInvoicer) {
    const v = form.value.invoiceRegistrationNumber.trim()
    if (!v) {
      registrationNumberError.value = t('receipt.settings.validation.registrationNumberRequired')
      ok = false
    } else if (!REGISTRATION_NUMBER_PATTERN.test(v)) {
      registrationNumberError.value = t('receipt.settings.validation.registrationNumberFormat')
      ok = false
    }
  }

  return ok
}

async function save() {
  if (!scopeId.value || !validate()) return
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      issuerName: form.value.issuerName,
      postalCode: form.value.postalCode || '',
      address: form.value.address || '',
      phone: form.value.phone || '',
      isQualifiedInvoicer: form.value.isQualifiedInvoicer,
      // OFF で保存する場合は明示的に空文字を送って DB からも消す（§9.2 トグル挙動5）
      invoiceRegistrationNumber: form.value.isQualifiedInvoicer
        ? form.value.invoiceRegistrationNumber
        : '',
      defaultSealVariant: form.value.defaultSealVariant,
      receiptNoteTemplate: form.value.receiptNoteTemplate || '',
      receiptNumberPrefix: form.value.receiptNumberPrefix || '',
      fiscalYearStartMonth: form.value.fiscalYearStartMonth,
      autoResetNumber: form.value.autoResetNumber,
      customFooter: form.value.customFooter || '',
    }
    const res = await updateSettings(scopeType.value, scopeId.value, body)
    applyResponse(res.data)
    success(t('receipt.settings.toast.saved'))
    if (form.value.isQualifiedInvoicer || form.value.invoiceRegistrationNumber) {
      success(t('receipt.settings.notRetroactiveAfterSave'))
    }
  } catch (err) {
    const code = extractErrorCode(err)
    if (code === 'RECEIPT_007') {
      registrationNumberError.value = t('receipt.settings.validation.registrationNumberRequired')
    } else if (code === 'RECEIPT_006') {
      registrationNumberError.value = t('receipt.settings.validation.registrationNumberFormat')
    } else if (extractStatus(err) === 403) {
      showError(t('receipt.settings.toast.forbidden'))
    } else {
      // AC-30: 500 等でもフォームの入力値は失わない（catch でリセットしない）
      showError(t('receipt.settings.toast.saveFailed'))
    }
  } finally {
    saving.value = false
  }
}

// === ロゴ ===
const logoUrl = ref<string | null>(null)
const logoUploading = ref(false)
const logoDeleting = ref(false)
const confirmDeleteLogo = ref(false)
const logoInput = ref<HTMLInputElement | null>(null)
const ALLOWED_LOGO_TYPES = ['image/png', 'image/jpeg']
const MAX_LOGO_BYTES = 1024 * 1024

// 発行者設定レコードが未作成の間はロゴ UI を disabled にする（§9.2）
const logoDisabled = computed(() => isFirstTime.value)

function onLogoInputClick() {
  logoInput.value?.click()
}

async function onLogoSelected(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0] ?? null
  target.value = ''
  if (!file || !scopeId.value) return

  if (!ALLOWED_LOGO_TYPES.includes(file.type)) {
    showError(t('receipt.settings.validation.logoUnsupportedType'))
    return
  }
  if (file.size > MAX_LOGO_BYTES) {
    showError(t('receipt.settings.validation.logoTooLarge'))
    return
  }

  const formData = new FormData()
  formData.append('file', file)
  logoUploading.value = true
  try {
    const res = await uploadLogo(scopeType.value, scopeId.value, formData)
    applyResponse(res.data)
    success(t('receipt.settings.toast.logoUploaded'))
  } catch {
    showError(t('receipt.settings.toast.logoUploadFailed'))
  } finally {
    logoUploading.value = false
  }
}

function openDeleteLogoConfirm() {
  confirmDeleteLogo.value = true
}

async function handleDeleteLogo() {
  if (!scopeId.value) return
  logoDeleting.value = true
  try {
    await deleteLogo(scopeType.value, scopeId.value)
    logoUrl.value = null
    confirmDeleteLogo.value = false
    success(t('receipt.settings.toast.logoDeleted'))
  } catch {
    showError(t('receipt.settings.toast.logoDeleteFailed'))
  } finally {
    logoDeleting.value = false
  }
}

// 未保存の変更があるまま離脱しようとした場合に確認する（ADHD 配慮・§9.5）
onBeforeRouteLeave(() => {
  if (dirty.value && !window.confirm(t('receipt.settings.hint.unsavedChanges'))) {
    return false
  }
  return true
})
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader
      :title="t('receipt.settings.title')"
      size="sm"
      back-to="/admin/receipts"
      :back-label="t('receipt.settings.backToList')"
    >
      <p class="text-sm text-surface-500">{{ t('receipt.settings.description') }}</p>
    </PageHeader>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- 既発行領収書への非遡及の明示（常設） -->
      <div class="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-800 dark:border-blue-800 dark:bg-blue-900/20 dark:text-blue-200">
        <i class="pi pi-info-circle mr-1" />
        {{ t('receipt.settings.notRetroactiveNotice') }}
      </div>

      <div v-if="isFirstTime" class="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-200">
        <i class="pi pi-exclamation-circle mr-1" />
        {{ t('receipt.settings.firstTimeNotice') }}
      </div>

      <div class="flex flex-col gap-4">
        <!-- 発行者情報 -->
        <SectionCard :title="t('receipt.settings.section.issuer')">
          <div class="flex flex-col gap-4">
            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('receipt.settings.form.issuerName') }} <span class="text-red-500">*</span>
              </label>
              <InputText
                v-model="form.issuerName"
                class="w-full"
                :maxlength="200"
                :placeholder="t('receipt.settings.placeholder.issuerName')"
                :invalid="!!issuerNameError"
              />
              <p v-if="issuerNameError" class="mt-1 text-xs text-red-500">{{ issuerNameError }}</p>
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.postalCode') }}</label>
              <InputText
                v-model="form.postalCode"
                class="w-full"
                :maxlength="10"
                :placeholder="t('receipt.settings.placeholder.postalCode')"
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.address') }}</label>
              <Textarea
                v-model="form.address"
                class="w-full"
                rows="2"
                :maxlength="500"
                :placeholder="t('receipt.settings.placeholder.address')"
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.phone') }}</label>
              <InputText
                v-model="form.phone"
                class="w-full"
                :maxlength="20"
                :placeholder="t('receipt.settings.placeholder.phone')"
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.logo') }}</label>
              <div class="flex items-center gap-4">
                <div class="flex h-16 w-32 items-center justify-center rounded border border-surface-200 bg-surface-50 dark:border-surface-700 dark:bg-surface-900">
                  <img v-if="logoUrl" :src="logoUrl" alt="" class="max-h-full max-w-full object-contain">
                  <span v-else class="text-xs text-surface-400">{{ t('receipt.settings.hint.logoNotSet') }}</span>
                </div>
                <div class="flex flex-col gap-1">
                  <div class="flex gap-2">
                    <Button
                      :label="t('receipt.settings.button.uploadLogo')"
                      size="small"
                      severity="secondary"
                      outlined
                      :disabled="logoDisabled"
                      :loading="logoUploading"
                      @click="onLogoInputClick"
                    />
                    <Button
                      v-if="logoUrl"
                      :label="t('receipt.settings.button.deleteLogo')"
                      size="small"
                      severity="danger"
                      outlined
                      :disabled="logoDisabled"
                      :loading="logoDeleting"
                      @click="openDeleteLogoConfirm"
                    />
                  </div>
                  <input
                    ref="logoInput"
                    type="file"
                    accept="image/png,image/jpeg"
                    class="hidden"
                    @change="onLogoSelected"
                  >
                  <p v-if="logoDisabled" class="text-xs text-surface-400">{{ t('receipt.settings.hint.logoDisabledUntilSaved') }}</p>
                  <p v-else class="text-xs text-surface-400">{{ t('receipt.settings.hint.logoResize') }}</p>
                  <p class="text-xs text-surface-400">{{ t('receipt.settings.hint.logoPosition') }}</p>
                </div>
              </div>
            </div>
          </div>
        </SectionCard>

        <!-- インボイス対応 -->
        <SectionCard :title="t('receipt.settings.section.invoice')">
          <div class="flex flex-col gap-4">
            <div class="flex items-center gap-3">
              <ToggleSwitch v-model="form.isQualifiedInvoicer" input-id="isQualifiedInvoicer" />
              <label for="isQualifiedInvoicer" class="text-sm font-medium">
                {{ t('receipt.settings.form.isQualifiedInvoicer') }}
              </label>
            </div>
            <p v-if="!form.isQualifiedInvoicer" class="text-xs text-surface-400">
              {{ t('receipt.settings.hint.registrationNumberDisabled') }}
            </p>
            <p v-if="!form.isQualifiedInvoicer && nextReceiptNumber !== null" class="text-xs text-amber-600">
              {{ t('receipt.settings.hint.registrationNumberWillBeCleared') }}
            </p>
            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('receipt.settings.form.invoiceRegistrationNumber') }}
                <span v-if="form.isQualifiedInvoicer" class="text-red-500">*</span>
              </label>
              <InputText
                v-model="form.invoiceRegistrationNumber"
                class="w-full"
                :maxlength="14"
                :disabled="!form.isQualifiedInvoicer"
                :placeholder="t('receipt.settings.placeholder.invoiceRegistrationNumber')"
                :invalid="!!registrationNumberError"
              />
              <p v-if="registrationNumberError" class="mt-1 text-xs text-red-500">{{ registrationNumberError }}</p>
            </div>
          </div>
        </SectionCard>

        <!-- 領収書番号 -->
        <Accordion :value="[]">
          <AccordionPanel value="numbering">
            <AccordionHeader>{{ t('receipt.settings.section.numbering') }}</AccordionHeader>
            <AccordionContent>
              <div class="flex flex-col gap-4">
                <div>
                  <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.receiptNumberPrefix') }}</label>
                  <InputText
                    v-model="form.receiptNumberPrefix"
                    class="w-full"
                    :maxlength="20"
                    :placeholder="t('receipt.settings.placeholder.receiptNumberPrefix')"
                  />
                </div>
                <div>
                  <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.fiscalYearStartMonth') }}</label>
                  <Select
                    v-model="form.fiscalYearStartMonth"
                    :options="fiscalMonthOptions"
                    option-label="label"
                    option-value="value"
                    class="w-32"
                  />
                </div>
                <div class="flex items-center gap-3">
                  <ToggleSwitch v-model="form.autoResetNumber" input-id="autoResetNumber" />
                  <label for="autoResetNumber" class="text-sm font-medium">
                    {{ t('receipt.settings.form.autoResetNumber') }}
                  </label>
                </div>
                <div>
                  <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.nextReceiptNumber') }}</label>
                  <p class="text-sm text-surface-500">
                    {{
                      nextReceiptNumber !== null
                        ? t('receipt.settings.hint.nextReceiptNumberFormat', {
                          prefix: form.receiptNumberPrefix,
                          number: nextReceiptNumber,
                        })
                        : t('receipt.settings.hint.notFirstYet')
                    }}
                  </p>
                </div>
              </div>
            </AccordionContent>
          </AccordionPanel>
        </Accordion>

        <!-- PDF体裁 -->
        <Accordion :value="[]">
          <AccordionPanel value="pdf">
            <AccordionHeader>{{ t('receipt.settings.section.pdf') }}</AccordionHeader>
            <AccordionContent>
              <div class="flex flex-col gap-4">
                <div>
                  <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.defaultSealVariant') }}</label>
                  <Select
                    v-model="form.defaultSealVariant"
                    :options="sealVariantOptions"
                    option-label="label"
                    option-value="value"
                    show-clear
                    class="w-48"
                  />
                </div>
                <div>
                  <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.receiptNoteTemplate') }}</label>
                  <Textarea
                    v-model="form.receiptNoteTemplate"
                    class="w-full"
                    rows="2"
                    :placeholder="t('receipt.settings.placeholder.receiptNoteTemplate')"
                  />
                </div>
                <div>
                  <label class="mb-1 block text-sm font-medium">{{ t('receipt.settings.form.customFooter') }}</label>
                  <Textarea
                    v-model="form.customFooter"
                    class="w-full"
                    rows="2"
                    :maxlength="500"
                    :placeholder="t('receipt.settings.placeholder.customFooter')"
                  />
                </div>
              </div>
            </AccordionContent>
          </AccordionPanel>
        </Accordion>
      </div>

      <div class="mt-6 flex justify-end">
        <Button
          :label="t('receipt.settings.button.save')"
          icon="pi pi-check"
          :loading="saving"
          @click="save"
        />
      </div>
    </template>

    <!-- ロゴ削除確認ダイアログ -->
    <Dialog
      v-model:visible="confirmDeleteLogo"
      :header="t('receipt.settings.form.logo')"
      :style="{ width: '400px' }"
      modal
      :draggable="false"
    >
      <p>{{ t('receipt.settings.button.deleteLogoConfirm') }}</p>
      <template #footer>
        <Button :label="t('receipt.settings.button.cancel')" severity="secondary" text @click="confirmDeleteLogo = false" />
        <Button
          :label="t('receipt.settings.button.confirmDelete')"
          icon="pi pi-trash"
          severity="danger"
          :loading="logoDeleting"
          @click="handleDeleteLogo"
        />
      </template>
    </Dialog>
  </div>
</template>
