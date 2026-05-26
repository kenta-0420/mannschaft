<script setup lang="ts">
/**
 * F01.10 マイページ履歴書・職務経歴書 — エディタページ（フル一括保存）。
 *
 * 機能:
 * - プロフィール情報（氏名・カナ・生年月日・性別）を読取専用で表示
 * - ヘッダ項目（住所・連絡先・通勤時間・配偶者・扶養家族）の入力
 * - 学歴 / 職歴 / 免許資格 / スキル の追加・削除・並び替え
 * - 志望動機 / 自己 PR / 本人希望 / 職務要約 / skills_summary のテキストエリア
 * - era_format トグル（西暦 / 和暦）
 * - autosave: 入力停止後 1.5 秒で PUT debounce
 * - 保存フィードバック: 保存中インジケータ・成功トースト・409 競合時は再取得促進
 * - プレビュー・出力ボタン（書類種別 × 形式）
 */
import type {
  ResumeDetail,
  ResumeCareer,
  DocumentType,
  OutputFormat,
} from '~/types/resume'

const { t } = useI18n()
const route = useRoute()
const { getResume, saveResume, uploadPhoto, deletePhoto, exportResume } = useResumeApi()
const { success, error, warn } = useNotification()
const fileInput = ref<HTMLInputElement | null>(null)

definePageMeta({ middleware: 'auth' })

const resumeId = computed(() => route.params.id as string)

useHead({ title: () => t('common.resume.title') })

// === 状態 ===
const loading = ref(true)
const saveStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const form = ref<ResumeDetail | null>(null)
const exportLoading = ref(false)
const photoLoading = ref(false)
let saveTimer: ReturnType<typeof setTimeout> | null = null

// === 初期読み込み ===
async function fetchResume() {
  loading.value = true
  try {
    const res = await getResume(resumeId.value)
    form.value = res.data
  }
  catch (e) {
    error(t('common.resume.loadError'), String(e))
  }
  finally {
    loading.value = false
  }
}

// === autosave（debounce 1.5 秒）===
function triggerAutoSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(doSave, 1500)
}

async function doSave() {
  if (!form.value) return
  saveStatus.value = 'saving'
  try {
    const payload = {
      ...form.value,
      educations: form.value.educations.filter(e => e.description?.trim()),
      careers: form.value.careers.filter(c => c.companyName?.trim()),
      qualifications: form.value.qualifications.filter(q => q.name?.trim()),
      skills: form.value.skills.filter(s => s.skillName?.trim()),
    }
    const res = await saveResume(resumeId.value, payload)
    // バージョンを最新値で更新（楽観ロック）
    form.value.version = res.data.version
    saveStatus.value = 'saved'
    // 3 秒後に idle に戻す
    setTimeout(() => {
      if (saveStatus.value === 'saved') saveStatus.value = 'idle'
    }, 3000)
  }
  catch (e: unknown) {
    const apiError = e as { status?: number }
    if (apiError?.status === 409) {
      // 楽観ロック競合 → 最新を再取得
      warn(t('common.resume.conflictError'))
      await fetchResume()
    }
    else {
      error(t('common.resume.saveError'), String(e))
    }
    saveStatus.value = 'error'
  }
}

// フォーム変更を監視してautosaveをトリガー
watch(
  form,
  () => {
    if (!loading.value && form.value) triggerAutoSave()
  },
  { deep: true },
)

// === 学歴操作 ===
function addEducation() {
  form.value?.educations.push({
    entryYear: new Date().getFullYear(),
    entryMonth: null,
    description: '',
    displayOrder: (form.value.educations.length),
  })
}

function removeEducation(index: number) {
  form.value?.educations.splice(index, 1)
  reorderEducations()
}

function moveEducation(index: number, direction: -1 | 1) {
  if (!form.value) return
  const arr = form.value.educations
  const newIdx = index + direction
  if (newIdx < 0 || newIdx >= arr.length) return
  const tmp = arr[index]!
  arr[index] = arr[newIdx]!
  arr[newIdx] = tmp
  reorderEducations()
}

function reorderEducations() {
  form.value?.educations.forEach((e, i) => { e.displayOrder = i })
}

// === 職歴操作 ===
function addCareer() {
  form.value?.careers.push({
    entryYear: new Date().getFullYear(),
    entryMonth: null,
    endYear: null,
    endMonth: null,
    isCurrent: true,
    companyName: '',
    department: null,
    employmentType: null,
    businessSummary: null,
    jobDescription: null,
    achievements: null,
    includeInRirekisho: true,
    includeInShokumukeireki: true,
    displayOrder: (form.value?.careers.length ?? 0),
  } as ResumeCareer)
}

function removeCareer(index: number) {
  form.value?.careers.splice(index, 1)
  reorderCareers()
}

function moveCareer(index: number, direction: -1 | 1) {
  if (!form.value) return
  const arr = form.value.careers
  const newIdx = index + direction
  if (newIdx < 0 || newIdx >= arr.length) return
  const tmp = arr[index]!
  arr[index] = arr[newIdx]!
  arr[newIdx] = tmp
  reorderCareers()
}

function reorderCareers() {
  form.value?.careers.forEach((c, i) => { c.displayOrder = i })
}

// === 資格操作 ===
function addQualification() {
  form.value?.qualifications.push({
    acquiredYear: new Date().getFullYear(),
    acquiredMonth: null,
    name: '',
    note: null,
    displayOrder: (form.value.qualifications.length),
  })
}

function removeQualification(index: number) {
  form.value?.qualifications.splice(index, 1)
  reorderQualifications()
}

function moveQualification(index: number, direction: -1 | 1) {
  if (!form.value) return
  const arr = form.value.qualifications
  const newIdx = index + direction
  if (newIdx < 0 || newIdx >= arr.length) return
  const tmp = arr[index]!
  arr[index] = arr[newIdx]!
  arr[newIdx] = tmp
  reorderQualifications()
}

function reorderQualifications() {
  form.value?.qualifications.forEach((q, i) => { q.displayOrder = i })
}

// === スキル操作 ===
function addSkill() {
  form.value?.skills.push({
    skillName: '',
    level: null,
    description: null,
    displayOrder: (form.value.skills.length),
  })
}

function removeSkill(index: number) {
  form.value?.skills.splice(index, 1)
  reorderSkills()
}

function reorderSkills() {
  form.value?.skills.forEach((s, i) => { s.displayOrder = i })
}

// === 証明写真 ===
function triggerFileSelect() {
  fileInput.value?.click()
}

async function handlePhotoSelect(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  photoLoading.value = true
  try {
    const res = await uploadPhoto(resumeId.value, file)
    if (form.value) form.value.photoUrl = res.data.photoUrl
    success(t('common.resume.photoUploadSuccess'))
  }
  catch (e) {
    error(t('common.resume.photoUploadError'), String(e))
  }
  finally {
    photoLoading.value = false
    // ファイル選択をリセット
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function handleDeletePhoto() {
  photoLoading.value = true
  try {
    await deletePhoto(resumeId.value)
    if (form.value) form.value.photoUrl = null
    success(t('common.resume.photoDeleteSuccess'))
  }
  catch (e) {
    error(t('common.resume.photoDeleteError'), String(e))
  }
  finally {
    photoLoading.value = false
  }
}

// === 出力 ===
async function handleExport(type: DocumentType, format: OutputFormat) {
  exportLoading.value = true
  try {
    const res = await exportResume(resumeId.value, type, format)
    // presigned URL をダウンロード
    const a = document.createElement('a')
    a.href = res.data.downloadUrl
    a.download = res.data.fileName
    a.click()
    success(t('common.resume.exportSuccess'))
  }
  catch (e) {
    error(t('common.resume.exportError'), String(e))
  }
  finally {
    exportLoading.value = false
  }
}

// スキルレベル選択肢
const skillLevelOptions = [
  { label: t('common.resume.skillLevels.BEGINNER'), value: 'BEGINNER' },
  { label: t('common.resume.skillLevels.INTERMEDIATE'), value: 'INTERMEDIATE' },
  { label: t('common.resume.skillLevels.ADVANCED'), value: 'ADVANCED' },
  { label: t('common.resume.skillLevels.EXPERT'), value: 'EXPERT' },
]

onMounted(fetchResume)

onBeforeUnmount(() => {
  if (saveTimer) clearTimeout(saveTimer)
})
</script>

<template>
  <div class="mx-auto max-w-4xl px-4 py-6">
    <!-- ヘッダー -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-3">
        <NuxtLink
          to="/my/resume"
          class="flex items-center gap-1 text-sm text-surface-500 hover:text-primary"
        >
          <i class="pi pi-arrow-left" />
          {{ t('common.resume.backToList') }}
        </NuxtLink>
        <h1 class="text-2xl font-bold">
          {{ form?.title ?? t('common.resume.title') }}
        </h1>
      </div>

      <!-- 保存ステータス -->
      <div class="flex items-center gap-2 text-sm">
        <span v-if="saveStatus === 'saving'" class="flex items-center gap-1 text-primary">
          <i class="pi pi-spin pi-spinner" />
          {{ t('common.resume.saving') }}
        </span>
        <span v-else-if="saveStatus === 'saved'" class="flex items-center gap-1 text-green-600">
          <i class="pi pi-check" />
          {{ t('common.resume.saved') }}
        </span>
        <span v-else-if="saveStatus === 'error'" class="flex items-center gap-1 text-red-500">
          <i class="pi pi-exclamation-circle" />
          {{ t('common.resume.saveError') }}
        </span>
      </div>
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <div v-else-if="form" class="space-y-6">
      <!-- 出力ボタン（上部） -->
      <SectionCard>
        <div class="flex flex-wrap gap-2">
          <NuxtLink :to="`/my/resume/${resumeId}/preview?type=rirekisho&format=pdf`">
            <Button
              :label="t('common.resume.previewPdf') + '（' + t('common.resume.rirekisho') + '）'"
              icon="pi pi-eye"
              severity="secondary"
              size="small"
            />
          </NuxtLink>
          <NuxtLink :to="`/my/resume/${resumeId}/preview?type=shokumukeirekisho&format=pdf`">
            <Button
              :label="t('common.resume.previewPdf') + '（' + t('common.resume.shokumukeirekisho') + '）'"
              icon="pi pi-eye"
              severity="secondary"
              size="small"
            />
          </NuxtLink>
          <Button
            :label="t('common.resume.downloadPdf') + '（' + t('common.resume.rirekisho') + '）'"
            icon="pi pi-file-pdf"
            size="small"
            :loading="exportLoading"
            @click="handleExport('rirekisho', 'pdf')"
          />
          <Button
            :label="t('common.resume.downloadPdf') + '（' + t('common.resume.shokumukeirekisho') + '）'"
            icon="pi pi-file-pdf"
            size="small"
            severity="secondary"
            :loading="exportLoading"
            @click="handleExport('shokumukeirekisho', 'pdf')"
          />
          <Button
            :label="t('common.resume.downloadExcel') + '（' + t('common.resume.rirekisho') + '）'"
            icon="pi pi-file-excel"
            size="small"
            severity="secondary"
            :loading="exportLoading"
            @click="handleExport('rirekisho', 'excel')"
          />
          <Button
            :label="t('common.resume.downloadExcel') + '（' + t('common.resume.shokumukeirekisho') + '）'"
            icon="pi pi-file-excel"
            size="small"
            severity="secondary"
            :loading="exportLoading"
            @click="handleExport('shokumukeirekisho', 'excel')"
          />
        </div>
      </SectionCard>

      <!-- バージョン名・年表記 -->
      <SectionCard>
        <h2 class="mb-4 text-lg font-semibold">{{ t('common.resume.headerSection') }}</h2>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('common.resume.versionTitle') }}</label>
            <InputText v-model="form.title" class="w-full" />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('common.resume.eraFormat') }}</label>
            <div class="flex gap-3">
              <label class="flex items-center gap-2 cursor-pointer">
                <RadioButton v-model="form.eraFormat" value="WESTERN" />
                {{ t('common.resume.western') }}
              </label>
              <label class="flex items-center gap-2 cursor-pointer">
                <RadioButton v-model="form.eraFormat" value="JAPANESE" />
                {{ t('common.resume.japanese') }}
              </label>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- プロフィール情報（読取専用） -->
      <SectionCard>
        <h2 class="mb-2 text-lg font-semibold">
          {{ t('common.resume.profileNote').split('は')[0] }}
        </h2>
        <p class="mb-3 text-sm text-surface-500">
          <i class="pi pi-info-circle mr-1" />
          {{ t('common.resume.profileNote') }}
        </p>
        <div class="rounded-lg bg-surface-50 p-3 text-sm text-surface-600 dark:bg-surface-700">
          <span class="flex items-center gap-2">
            <i class="pi pi-lock text-surface-400" />
            {{ t('common.resume.readOnlyField') }}
          </span>
        </div>
      </SectionCard>

      <!-- 証明写真 -->
      <SectionCard>
        <h2 class="mb-4 text-lg font-semibold">{{ t('common.resume.photoUpload') }}</h2>
        <div class="flex items-start gap-4">
          <div
            class="flex h-32 w-24 items-center justify-center overflow-hidden rounded-lg border-2 border-dashed border-surface-300 bg-surface-50 dark:border-surface-600 dark:bg-surface-700"
          >
            <img
              v-if="form.photoUrl"
              :src="form.photoUrl"
              alt="証明写真"
              class="h-full w-full object-cover"
            >
            <i v-else class="pi pi-image text-3xl text-surface-300" />
          </div>
          <div class="flex flex-col gap-2">
            <Button
              :label="t('common.resume.uploadPhoto')"
              icon="pi pi-upload"
              size="small"
              :loading="photoLoading"
              @click="triggerFileSelect"
            />
            <Button
              v-if="form.photoUrl"
              :label="t('common.resume.deletePhoto')"
              icon="pi pi-trash"
              size="small"
              severity="danger"
              text
              :loading="photoLoading"
              @click="handleDeletePhoto"
            />
            <p class="text-xs text-surface-500">{{ t('common.resume.photoHint') }}</p>
          </div>
        </div>
        <input
          ref="fileInput"
          type="file"
          accept="image/jpeg,image/png"
          class="hidden"
          @change="handlePhotoSelect"
        >
      </SectionCard>

      <!-- 住所・連絡先 -->
      <SectionCard>
        <h2 class="mb-4 text-lg font-semibold">{{ t('common.resume.addressSection') }}</h2>
        <div class="grid grid-cols-1 gap-4">
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.currentAddress') }}</label>
              <InputText v-model="form.currentAddress" class="w-full" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.currentAddressKana') }}</label>
              <InputText v-model="form.currentAddressKana" class="w-full" />
            </div>
          </div>
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.contactAddress') }}</label>
              <InputText v-model="form.contactAddress" class="w-full" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.contactAddressKana') }}</label>
              <InputText v-model="form.contactAddressKana" class="w-full" />
            </div>
          </div>
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.contactPhone') }}</label>
              <InputText v-model="form.contactPhone" class="w-full" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.contactEmail') }}</label>
              <InputText v-model="form.contactEmail" class="w-full" type="email" />
            </div>
          </div>
          <!-- 任意欄（厚労省 2021 様式準拠） -->
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.commuteMinutes') }}</label>
              <InputNumber v-model="form.commuteMinutes" :min="0" :max="999" class="w-full" />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">{{ t('common.resume.dependentsCount') }}</label>
              <InputNumber v-model="form.dependentsCount" :min="0" :max="99" class="w-full" />
            </div>
            <div class="flex flex-col gap-2 pt-6">
              <label class="flex items-center gap-2 cursor-pointer">
                <Checkbox v-model="form.hasSpouse" :binary="true" />
                {{ t('common.resume.hasSpouse') }}
              </label>
              <label class="flex items-center gap-2 cursor-pointer">
                <Checkbox v-model="form.spouseSupport" :binary="true" />
                {{ t('common.resume.spouseSupport') }}
              </label>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- 学歴 -->
      <SectionCard>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-lg font-semibold">{{ t('common.resume.educations') }}</h2>
          <Button
            :label="t('common.resume.addRow')"
            icon="pi pi-plus"
            size="small"
            @click="addEducation"
          />
        </div>
        <div v-if="form.educations.length === 0" class="text-sm text-surface-400">
          {{ t('common.resume.addRow') }}
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="(edu, idx) in form.educations"
            :key="idx"
            class="rounded-lg border border-surface-200 p-3 dark:border-surface-600"
          >
            <div class="space-y-2">
              <!-- 上行: 年・月 + 操作ボタン -->
              <div class="flex items-end gap-2">
                <div class="flex-shrink-0">
                  <label class="mb-1 block text-xs">{{ t('common.resume.entryYear') }}</label>
                  <InputNumber v-model="edu.entryYear" :min="1900" :max="2099" :use-grouping="false" class="w-24" inputClass="w-full" />
                </div>
                <div class="flex-shrink-0">
                  <label class="mb-1 block text-xs">{{ t('common.resume.entryMonth') }}</label>
                  <InputNumber v-model="edu.entryMonth" :min="1" :max="12" class="w-16" inputClass="w-full" />
                </div>
                <div class="ml-auto flex items-end gap-1">
                  <Button
                    icon="pi pi-arrow-up"
                    text
                    size="small"
                    :aria-label="t('common.resume.moveUp')"
                    :disabled="idx === 0"
                    @click="moveEducation(idx, -1)"
                  />
                  <Button
                    icon="pi pi-arrow-down"
                    text
                    size="small"
                    :aria-label="t('common.resume.moveDown')"
                    :disabled="idx === form.educations.length - 1"
                    @click="moveEducation(idx, 1)"
                  />
                  <Button
                    icon="pi pi-trash"
                    text
                    size="small"
                    severity="danger"
                    :aria-label="t('common.resume.deleteRow')"
                    @click="removeEducation(idx)"
                  />
                </div>
              </div>
              <!-- 下行: 内容（全幅） -->
              <div>
                <label class="mb-1 block text-xs">{{ t('common.resume.description') }}</label>
                <InputText v-model="edu.description" class="w-full" />
              </div>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- 職歴 -->
      <SectionCard>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-lg font-semibold">{{ t('common.resume.careers') }}</h2>
          <Button
            :label="t('common.resume.addRow')"
            icon="pi pi-plus"
            size="small"
            @click="addCareer"
          />
        </div>
        <div v-if="form.careers.length === 0" class="text-sm text-surface-400">
          {{ t('common.resume.addRow') }}
        </div>
        <div v-else class="space-y-4">
          <div
            v-for="(career, idx) in form.careers"
            :key="idx"
            class="rounded-lg border border-surface-200 p-5 shadow-sm transition-colors hover:border-surface-300 dark:border-surface-600 dark:hover:border-surface-500"
          >
            <!-- 操作ボタン帯 -->
            <div class="-mx-5 -mt-5 mb-4 flex justify-end gap-1 rounded-t-lg border-b border-surface-200 bg-surface-50 px-3 py-1.5 dark:border-surface-600 dark:bg-surface-800">
              <Button
                icon="pi pi-arrow-up"
                text
                size="small"
                :aria-label="t('common.resume.moveUp')"
                :disabled="idx === 0"
                @click="moveCareer(idx, -1)"
              />
              <Button
                icon="pi pi-arrow-down"
                text
                size="small"
                :aria-label="t('common.resume.moveDown')"
                :disabled="idx === form.careers.length - 1"
                @click="moveCareer(idx, 1)"
              />
              <Button
                icon="pi pi-trash"
                text
                size="small"
                severity="danger"
                :aria-label="t('common.resume.deleteRow')"
                @click="removeCareer(idx)"
              />
            </div>
            <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <label class="mb-1 block text-sm">{{ t('common.resume.companyName') }} *</label>
                <InputText v-model="career.companyName" class="w-full" />
              </div>
              <div>
                <label class="mb-1 block text-sm">{{ t('common.resume.department') }}</label>
                <InputText v-model="career.department" class="w-full" />
              </div>
              <div>
                <label class="mb-1 block text-sm">{{ t('common.resume.employmentType') }}</label>
                <InputText v-model="career.employmentType" class="w-full" />
              </div>
              <!-- 入社/退社日 グループ -->
              <div class="sm:col-span-2">
                <div class="rounded-md bg-surface-50 p-3 dark:bg-surface-800">
                  <div class="flex flex-wrap items-end gap-6">
                    <div>
                      <label class="mb-1.5 block text-sm font-medium">{{ t('common.resume.entryDate') }}</label>
                      <div class="flex items-center gap-2">
                        <div class="flex items-baseline gap-1">
                          <InputNumber v-model="career.entryYear" :min="1900" :max="2099" :use-grouping="false" class="w-24" inputClass="w-full" />
                          <span class="text-sm text-surface-600 dark:text-surface-400">{{ t('common.resume.entryYear') }}</span>
                        </div>
                        <div class="flex items-baseline gap-1">
                          <InputNumber v-model="career.entryMonth" :min="1" :max="12" class="w-16" inputClass="w-full" />
                          <span class="text-sm text-surface-600 dark:text-surface-400">{{ t('common.resume.entryMonth') }}</span>
                        </div>
                      </div>
                    </div>
                    <div v-if="!career.isCurrent">
                      <label class="mb-1.5 block text-sm font-medium">{{ t('common.resume.endDate') }}</label>
                      <div class="flex items-center gap-2">
                        <div class="flex items-baseline gap-1">
                          <InputNumber v-model="career.endYear" :min="1900" :max="2099" :use-grouping="false" class="w-24" inputClass="w-full" />
                          <span class="text-sm text-surface-600 dark:text-surface-400">{{ t('common.resume.entryYear') }}</span>
                        </div>
                        <div class="flex items-baseline gap-1">
                          <InputNumber v-model="career.endMonth" :min="1" :max="12" class="w-16" inputClass="w-full" />
                          <span class="text-sm text-surface-600 dark:text-surface-400">{{ t('common.resume.entryMonth') }}</span>
                        </div>
                      </div>
                    </div>
                    <label class="flex cursor-pointer items-center gap-2 self-end pb-0.5">
                      <Checkbox v-model="career.isCurrent" :binary="true" />
                      <span class="text-sm">{{ t('common.resume.isCurrent') }}</span>
                    </label>
                  </div>
                </div>
              </div>
              <!-- チェックボックス群 -->
              <div class="sm:col-span-2">
                <div class="flex flex-wrap gap-6">
                  <label class="flex cursor-pointer items-center gap-2">
                    <Checkbox v-model="career.includeInRirekisho" :binary="true" />
                    <span class="text-sm">{{ t('common.resume.includeInRirekisho') }}</span>
                  </label>
                  <label class="flex cursor-pointer items-center gap-2">
                    <Checkbox v-model="career.includeInShokumukeireki" :binary="true" />
                    <span class="text-sm">{{ t('common.resume.includeInShokumukeireki') }}</span>
                  </label>
                </div>
              </div>
            </div>
            <!-- 職務経歴書用フィールド -->
            <div class="mt-4 space-y-3">
              <div>
                <label class="mb-1 block text-sm">{{ t('common.resume.businessSummary') }}</label>
                <InputText v-model="career.businessSummary" class="w-full" />
              </div>
              <div>
                <label class="mb-1 block text-sm">{{ t('common.resume.jobDescription') }}</label>
                <Textarea v-model="career.jobDescription" rows="3" class="w-full" auto-resize />
              </div>
              <div>
                <label class="mb-1 block text-sm">{{ t('common.resume.achievements') }}</label>
                <Textarea v-model="career.achievements" rows="2" class="w-full" auto-resize />
              </div>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- 免許・資格 -->
      <SectionCard>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-lg font-semibold">{{ t('common.resume.qualifications') }}</h2>
          <Button
            :label="t('common.resume.addRow')"
            icon="pi pi-plus"
            size="small"
            @click="addQualification"
          />
        </div>
        <div v-if="form.qualifications.length === 0" class="text-sm text-surface-400">
          {{ t('common.resume.addRow') }}
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="(qual, idx) in form.qualifications"
            :key="idx"
            class="space-y-2 rounded-lg border border-surface-200 p-3 dark:border-surface-600"
          >
            <!-- 上行: 取得年月 + 操作ボタン -->
            <div class="flex items-end gap-2">
              <div class="flex-shrink-0">
                <label class="mb-1 block text-xs">{{ t('common.resume.acquiredYear') }}</label>
                <InputNumber v-model="qual.acquiredYear" :min="1900" :max="2099" :use-grouping="false" class="w-24" inputClass="w-full" />
              </div>
              <div class="flex-shrink-0">
                <label class="mb-1 block text-xs">{{ t('common.resume.acquiredMonth') }}</label>
                <InputNumber v-model="qual.acquiredMonth" :min="1" :max="12" class="w-16" inputClass="w-full" />
              </div>
              <div class="ml-auto flex items-end gap-1">
                <Button
                  icon="pi pi-arrow-up"
                  text
                  size="small"
                  :disabled="idx === 0"
                  @click="moveQualification(idx, -1)"
                />
                <Button
                  icon="pi pi-arrow-down"
                  text
                  size="small"
                  :disabled="idx === form.qualifications.length - 1"
                  @click="moveQualification(idx, 1)"
                />
                <Button
                  icon="pi pi-trash"
                  text
                  size="small"
                  severity="danger"
                  @click="removeQualification(idx)"
                />
              </div>
            </div>
            <!-- 下行: 資格名・備考 -->
            <div class="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <div>
                <label class="mb-1 block text-xs">{{ t('common.resume.qualificationName') }} *</label>
                <InputText v-model="qual.name" class="w-full" />
              </div>
              <div>
                <label class="mb-1 block text-xs">{{ t('common.resume.note') }}</label>
                <InputText v-model="qual.note" class="w-full" />
              </div>
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- 志望動機 / 自己PR / 本人希望 -->
      <SectionCard>
        <h2 class="mb-4 text-lg font-semibold">{{ t('common.resume.rirekisho') }}</h2>
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('common.resume.motivation') }}</label>
            <Textarea v-model="form.motivation" rows="4" class="w-full" auto-resize />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('common.resume.selfPr') }}</label>
            <Textarea v-model="form.selfPr" rows="4" class="w-full" auto-resize />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('common.resume.personalRequest') }}</label>
            <Textarea v-model="form.personalRequest" rows="3" class="w-full" auto-resize />
          </div>
        </div>
      </SectionCard>

      <!-- 職務要約 / skills_summary（職務経歴書用） -->
      <SectionCard>
        <h2 class="mb-4 text-lg font-semibold">{{ t('common.resume.shokumukeirekisho') }}</h2>
        <div class="space-y-4">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('common.resume.careerSummary') }}</label>
            <Textarea v-model="form.careerSummary" rows="4" class="w-full" auto-resize />
          </div>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ t('common.resume.skillsSummary') }}</label>
            <p class="mb-1 text-xs text-surface-500">{{ t('common.resume.skillsSummaryHint') }}</p>
            <Textarea v-model="form.skillsSummary" rows="4" class="w-full" auto-resize />
          </div>
        </div>
      </SectionCard>

      <!-- スキル（職務経歴書用・任意） -->
      <SectionCard>
        <div class="mb-4 flex items-center justify-between">
          <h2 class="text-lg font-semibold">{{ t('common.resume.skills') }}</h2>
          <Button
            :label="t('common.resume.addRow')"
            icon="pi pi-plus"
            size="small"
            @click="addSkill"
          />
        </div>
        <div v-if="form.skills.length === 0" class="text-sm text-surface-400">
          {{ t('common.resume.addRow') }}
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="(skill, idx) in form.skills"
            :key="idx"
            class="flex flex-wrap items-start gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-600"
          >
            <div class="min-w-32 flex-1">
              <label class="mb-1 block text-xs">{{ t('common.resume.skillName') }} *</label>
              <InputText v-model="skill.skillName" class="w-full" />
            </div>
            <div class="flex-shrink-0">
              <label class="mb-1 block text-xs">{{ t('common.resume.skillLevel') }}</label>
              <Select
                v-model="skill.level"
                :options="skillLevelOptions"
                option-label="label"
                option-value="value"
                :placeholder="t('common.resume.skillLevel')"
                class="w-36"
                show-clear
              />
            </div>
            <div class="min-w-48 flex-1">
              <label class="mb-1 block text-xs">{{ t('common.resume.description') }}</label>
              <InputText v-model="skill.description" class="w-full" />
            </div>
            <div class="flex flex-shrink-0 items-end">
              <Button
                icon="pi pi-trash"
                text
                size="small"
                severity="danger"
                @click="removeSkill(idx)"
              />
            </div>
          </div>
        </div>
      </SectionCard>

      <!-- 出力ボタン（下部） -->
      <SectionCard>
        <div class="flex flex-wrap gap-2">
          <Button
            :label="t('common.resume.downloadPdf') + '（' + t('common.resume.rirekisho') + '）'"
            icon="pi pi-file-pdf"
            :loading="exportLoading"
            @click="handleExport('rirekisho', 'pdf')"
          />
          <Button
            :label="t('common.resume.downloadPdf') + '（' + t('common.resume.shokumukeirekisho') + '）'"
            icon="pi pi-file-pdf"
            severity="secondary"
            :loading="exportLoading"
            @click="handleExport('shokumukeirekisho', 'pdf')"
          />
          <Button
            :label="t('common.resume.downloadExcel') + '（' + t('common.resume.rirekisho') + '）'"
            icon="pi pi-file-excel"
            severity="secondary"
            :loading="exportLoading"
            @click="handleExport('rirekisho', 'excel')"
          />
          <Button
            :label="t('common.resume.downloadExcel') + '（' + t('common.resume.shokumukeirekisho') + '）'"
            icon="pi pi-file-excel"
            severity="secondary"
            :loading="exportLoading"
            @click="handleExport('shokumukeirekisho', 'excel')"
          />
        </div>
      </SectionCard>
    </div>
  </div>
</template>
