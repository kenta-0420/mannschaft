<script setup lang="ts">
/**
 * F18 Phase 2 — 自店プロバイダーの新規発行 / 編集フォーム。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-8 / §12.1
 *
 * <p>新規（mode='create'）と編集（mode='edit'）で 1 ファイル共通化する。
 * {@code type} は Phase 2 では常に {@code SELF_ISSUED_STAMP} で固定送信のため UI に出さない。
 *
 * @emit submit 入力済みフォーム値（CreateOrgProviderRequest | UpdateOrgProviderRequest 互換）
 * @emit cancel キャンセル
 */
import type {
  CreateOrgProviderRequest,
  OrgPointCardProvider,
  UpdateOrgProviderRequest,
} from '~/types/orgPointCard'

interface Props {
  /** 初期値（編集モード時のみ） */
  initial?: OrgPointCardProvider | null
  mode: 'create' | 'edit'
  /** 保存中フラグ（親で API 呼び出し中に true） */
  submitting?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  initial: null,
  submitting: false,
})

const emit = defineEmits<{
  submit: [body: CreateOrgProviderRequest | UpdateOrgProviderRequest]
  cancel: []
}>()

const { t } = useI18n()

// フォーム状態
const displayName = ref(props.initial?.displayName ?? '')
const brandColor = ref(props.initial?.brandColor ?? '#3b82f6')
const logoUrl = ref(props.initial?.logoUrl ?? '')
const cardNumberRegex = ref('')
const cardNumberLengthHint = ref(props.initial?.cardNumberLengthHint ?? '')

// バリデーション
const errors = ref<Record<string, string>>({})

const HEX_PATTERN = /^#[0-9A-Fa-f]{6}$/

function validate(): boolean {
  errors.value = {}
  if (!displayName.value.trim()) {
    errors.value.displayName = t('wallet.admin.providers.validation.display_name_required')
  } else if (displayName.value.length > 100) {
    errors.value.displayName = t('wallet.admin.providers.validation.display_name_max')
  }
  if (brandColor.value && !HEX_PATTERN.test(brandColor.value)) {
    errors.value.brandColor = t('wallet.admin.providers.validation.brand_color_format')
  }
  if (logoUrl.value && logoUrl.value.length > 500) {
    errors.value.logoUrl = t('wallet.admin.providers.validation.logo_url_max')
  }
  if (cardNumberRegex.value && cardNumberRegex.value.length > 200) {
    errors.value.cardNumberRegex = t('wallet.admin.providers.validation.regex_max')
  }
  if (cardNumberLengthHint.value && cardNumberLengthHint.value.length > 50) {
    errors.value.cardNumberLengthHint = t('wallet.admin.providers.validation.hint_max')
  }
  return Object.keys(errors.value).length === 0
}

function onSubmit() {
  if (!validate()) return
  const body: CreateOrgProviderRequest = {
    displayName: displayName.value.trim(),
  }
  if (brandColor.value) body.brandColor = brandColor.value
  if (logoUrl.value.trim()) body.logoUrl = logoUrl.value.trim()
  if (cardNumberRegex.value.trim()) body.cardNumberRegex = cardNumberRegex.value.trim()
  if (cardNumberLengthHint.value.trim()) {
    body.cardNumberLengthHint = cardNumberLengthHint.value.trim()
  }
  emit('submit', body)
}
</script>

<template>
  <form class="space-y-4" @submit.prevent="onSubmit">
    <!-- 表示名 -->
    <div>
      <label for="provider-display-name" class="block text-sm font-medium mb-1">
        {{ t('wallet.admin.providers.display_name') }}
        <span class="text-red-500">*</span>
      </label>
      <input
        id="provider-display-name"
        v-model="displayName"
        type="text"
        class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
        :placeholder="t('wallet.admin.providers.display_name_placeholder')"
        :aria-invalid="!!errors.displayName"
        maxlength="100"
      >
      <p v-if="errors.displayName" class="mt-1 text-xs text-red-600" role="alert">
        {{ errors.displayName }}
      </p>
    </div>

    <!-- ブランドカラー -->
    <div>
      <label for="provider-brand-color" class="block text-sm font-medium mb-1">
        {{ t('wallet.admin.providers.brand_color') }}
      </label>
      <div class="flex items-center gap-2">
        <input
          id="provider-brand-color"
          v-model="brandColor"
          type="color"
          class="h-10 w-16 rounded border border-surface-300 dark:border-surface-600"
        >
        <input
          v-model="brandColor"
          type="text"
          class="flex-1 rounded border border-surface-300 px-3 py-2 font-mono dark:border-surface-600 dark:bg-surface-800"
          placeholder="#3b82f6"
          :aria-invalid="!!errors.brandColor"
          maxlength="7"
        >
      </div>
      <p v-if="errors.brandColor" class="mt-1 text-xs text-red-600" role="alert">
        {{ errors.brandColor }}
      </p>
    </div>

    <!-- ロゴURL -->
    <div>
      <label for="provider-logo-url" class="block text-sm font-medium mb-1">
        {{ t('wallet.admin.providers.logo_url') }}
      </label>
      <input
        id="provider-logo-url"
        v-model="logoUrl"
        type="url"
        class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
        placeholder="https://..."
        :aria-invalid="!!errors.logoUrl"
        maxlength="500"
      >
      <p v-if="errors.logoUrl" class="mt-1 text-xs text-red-600" role="alert">
        {{ errors.logoUrl }}
      </p>
    </div>

    <!-- カード番号正規表現 -->
    <div>
      <label for="provider-regex" class="block text-sm font-medium mb-1">
        {{ t('wallet.admin.providers.card_number_regex') }}
      </label>
      <input
        id="provider-regex"
        v-model="cardNumberRegex"
        type="text"
        class="w-full rounded border border-surface-300 px-3 py-2 font-mono text-sm dark:border-surface-600 dark:bg-surface-800"
        placeholder="^[0-9]{10,16}$"
        :aria-invalid="!!errors.cardNumberRegex"
        maxlength="200"
      >
      <p class="mt-1 text-xs text-surface-500">
        {{ t('wallet.admin.providers.card_number_regex_hint') }}
      </p>
      <p v-if="errors.cardNumberRegex" class="mt-1 text-xs text-red-600" role="alert">
        {{ errors.cardNumberRegex }}
      </p>
    </div>

    <!-- カード番号桁数ヒント -->
    <div>
      <label for="provider-length-hint" class="block text-sm font-medium mb-1">
        {{ t('wallet.admin.providers.card_number_length_hint') }}
      </label>
      <input
        id="provider-length-hint"
        v-model="cardNumberLengthHint"
        type="text"
        class="w-full rounded border border-surface-300 px-3 py-2 dark:border-surface-600 dark:bg-surface-800"
        :placeholder="t('wallet.admin.providers.card_number_length_hint_placeholder')"
        :aria-invalid="!!errors.cardNumberLengthHint"
        maxlength="50"
      >
      <p v-if="errors.cardNumberLengthHint" class="mt-1 text-xs text-red-600" role="alert">
        {{ errors.cardNumberLengthHint }}
      </p>
    </div>

    <!-- ボタン -->
    <div class="flex justify-end gap-2 pt-2">
      <button
        type="button"
        class="rounded border border-surface-300 px-4 py-2 hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
        :disabled="submitting"
        @click="emit('cancel')"
      >
        {{ t('wallet.admin.actions.cancel') }}
      </button>
      <button
        type="submit"
        class="rounded bg-primary-600 px-4 py-2 font-medium text-white hover:bg-primary-700 disabled:opacity-50"
        :disabled="submitting"
      >
        {{ submitting ? t('wallet.admin.actions.saving') : t('wallet.admin.actions.save') }}
      </button>
    </div>
  </form>
</template>
