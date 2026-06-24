<script setup lang="ts">
import { resolveCountry } from '~/utils/resolveCountry'

const profile = defineModel<{
  nickname: string
  email: string
  phoneNumber: string
  postalCode: string
  avatarUrl: string | null
  isSearchable: boolean
  /** ISO 3166-1 alpha-2 国コード。未設定時は null。 */
  countryCode?: string | null
  /** 表示言語（例: "ja", "en-US"）。実効国フォールバックに使用。 */
  locale?: string
}>('profile', { required: true })

defineProps<{
  savingProfile: boolean
}>()

defineEmits<{
  save: []
  uploadAvatar: [event: Event]
}>()

const { t } = useI18n()
const { isLoaded, isSupported, validateFormat, ensureLoaded } = usePostalCodeValidation()

// ポリシーを事前ロード（onMounted で取得しておく）。
// _policies はリアクティブな ref なので、ロード完了時に下記 computed が自動再評価される。
onMounted(() => {
  ensureLoaded().catch(() => {
    // 取得失敗はサイレント（クライアント検証は best-effort）
  })
})

/** 実効国コード（countryCode → locale フォールバック） */
const effectiveCountry = computed<string | null>(() =>
  resolveCountry(profile.value.countryCode, profile.value.locale),
)

/**
 * 郵便番号バリデーションが対応している国かどうか。
 * ポリシー未ロード中（isLoaded=false）は false 扱い（必須マークを出さない）。
 */
const postalSupported = computed<boolean>(() => {
  if (!isLoaded.value) return false
  const cc = effectiveCountry.value
  return cc !== null && isSupported(cc)
})

/**
 * ポリシーがロード済み かつ 実効国が未対応 のときだけ true。
 * 未ロード中は false（誤って「未対応」注記を出さないため）。
 */
const postalUnsupportedRegion = computed<boolean>(() => {
  if (!isLoaded.value) return false
  const cc = effectiveCountry.value
  return cc !== null && !isSupported(cc)
})

/**
 * クライアントサイドの郵便番号エラー文言。
 * - ポリシー未ロード中: null（誤検証を出さない）
 * - 未対応国: null（検証スキップ）
 * - 対応国かつ空欄: 必須エラー
 * - 対応国かつフォーマット不正: フォーマットエラー
 * - 対応国かつ正常: null
 */
const postalCodeError = computed<string | null>(() => {
  if (!isLoaded.value) return null
  const cc = effectiveCountry.value
  if (!cc || !isSupported(cc)) return null

  const val = profile.value.postalCode ?? ''
  if (val.length === 0) return t('postal_code_required')
  if (!validateFormat(cc, val)) return t('postal_code_format')
  return null
})

/**
 * 保存ボタンを非活性にする条件。
 * 対応国でクライアント側エラーがある場合に保存を阻止する。
 */
const saveDisabled = computed<boolean>(() => postalCodeError.value !== null)
</script>

<template>
  <SectionCard :title="$t('settings.profile.section_title')">
    <div class="space-y-4">
      <div class="flex items-center gap-4">
        <div>
          <img
            v-if="profile.avatarUrl"
            :src="profile.avatarUrl"
            :alt="$t('settings.profile.avatar_alt')"
            class="h-20 w-20 rounded-full object-cover"
          >
          <div
            v-else
            class="flex h-20 w-20 items-center justify-center rounded-full bg-primary/10 text-2xl text-primary"
          >
            <i class="pi pi-user" />
          </div>
        </div>
        <div>
          <label class="cursor-pointer">
            <input
              type="file"
              accept="image/*"
              class="hidden"
              @change="$emit('uploadAvatar', $event)"
            >
            <Button
              translate="no"
              :label="$t('settings.profile.change_image')"
              icon="pi pi-upload"
              severity="secondary"
              size="small"
              as="span"
            />
          </label>
          <p class="mt-1 text-xs text-surface-500">{{ $t('settings.profile.image_hint') }}</p>
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.profile.display_name') }}</label>
        <InputText v-model="profile.nickname" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('settings.profile.phone_number') }}</label>
        <InputText v-model="profile.phoneNumber" class="w-full" :placeholder="$t('settings.profile.phone_placeholder')" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ $t('settings.profile.postal_code') }}
          <!-- 対応国では必須マーク -->
          <span v-if="postalSupported" class="ml-1 text-red-500">※</span>
        </label>
        <InputText
          v-model="profile.postalCode"
          class="w-full"
          :placeholder="$t('settings.profile.postal_placeholder')"
          :invalid="postalCodeError !== null"
        />
        <!-- クライアントサイド郵便番号エラー（対応国のみ表示） -->
        <small v-if="postalCodeError" class="mt-1 block text-red-500">
          {{ postalCodeError }}
        </small>
        <!-- AC-8: 未対応国への注記（ポリシーがロード済み かつ 未対応国のときだけ表示） -->
        <small v-else-if="postalUnsupportedRegion" class="mt-1 block text-surface-400">
          {{ $t('settings.profile.postal_unsupported_region') }}
        </small>
      </div>
      <div
        class="flex items-center justify-between rounded-lg border border-surface-300 p-3 dark:border-surface-600"
      >
        <div>
          <p class="text-sm font-medium">{{ $t('settings.profile.searchable_label') }}</p>
          <p class="text-xs text-surface-500">
            {{ $t('settings.profile.searchable_description') }}
          </p>
        </div>
        <ToggleSwitch v-model="profile.isSearchable" />
      </div>
      <div class="flex justify-end">
        <Button
          translate="no"
          :label="$t('button.save')"
          icon="pi pi-check"
          :loading="savingProfile"
          :disabled="saveDisabled"
          @click="$emit('save')"
        />
      </div>
    </div>
  </SectionCard>
</template>
