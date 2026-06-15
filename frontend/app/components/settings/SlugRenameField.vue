<script setup lang="ts">
import type { FetchError } from 'ofetch'
import type { SlugAvailabilityResponse, SlugUnavailableReason } from '~/types/slug'
import { isSlugFormatValid } from '~/utils/slug'

/**
 * チーム/組織の slug 変更（リネーム）フィールド（BE #1542・村方式）。
 *
 * - 現 slug を初期表示。可用性チェックは作成フォームと同じ EP を再利用（400ms デバウンス・レース対策）。
 * - 自分の現 slug は「変更なし（OK）」扱い（no-op）。
 * - 形式バリデーション（`isSlugFormatValid`）。無効 slug 時は保存ボタン disabled。
 * - 保存で `PUT /api/v1/{base}/{currentSlug}/slug` を呼び、成功時は親に新 slug を emit する
 *   （URL 遷移は親ページが担う。slug が変わると URL が変わるため）。
 *
 * 本コンポーネントは ADMIN/DEPUTY のみが表示する前提（呼び出し側で `v-if="isAdminOrDeputy"` ガード）。
 */
const props = defineProps<{
  entityType: 'team' | 'organization'
  /** 現在の slug（URL 用）。 */
  currentSlug: string
}>()

const emit = defineEmits<{
  /** リネーム成功時。新 slug を渡す（親が新 URL へ遷移する）。 */
  renamed: [newSlug: string]
}>()

const { t } = useI18n()
const toast = useToast()
const { checkTeamSlugAvailable, renameTeamSlug } = useTeamApi()
const { checkOrganizationSlugAvailable, renameOrganizationSlug } = useOrganizationApi()

const isTeam = computed(() => props.entityType === 'team')

// 入力 slug。初期値は現 slug。
const slug = ref(props.currentSlug)
type SlugStatus = 'idle' | 'checking' | 'available' | 'unavailable' | 'invalid'
const slugStatus = ref<SlugStatus>('idle')
const slugReason = ref<SlugUnavailableReason | null>(null)
const saving = ref(false)
let slugCheckTimer: ReturnType<typeof setTimeout> | null = null
// 連打レースで古い応答が新しい結果を上書きしないようにする世代カウンタ。
let slugCheckSeq = 0

/** 入力が現 slug と同一か（= 変更なし・no-op）。 */
const isUnchanged = computed(() => slug.value.trim() === props.currentSlug)

/** entityType に応じた可用性チェック EP を呼ぶ。 */
function checkSlugAvailable(target: string): Promise<SlugAvailabilityResponse> {
  return isTeam.value
    ? checkTeamSlugAvailable(target)
    : checkOrganizationSlugAvailable(target)
}

/** slug 入力ハンドラ。トリムして可用性チェックを予約する。 */
function onSlugInput() {
  slug.value = slug.value.trim()
  scheduleSlugCheck()
}

/** デバウンス（400ms）して可用性チェックを予約する。形式不正は即時表示し EP は叩かない。 */
function scheduleSlugCheck() {
  slugReason.value = null
  if (slugCheckTimer) {
    clearTimeout(slugCheckTimer)
    slugCheckTimer = null
  }
  const current = slug.value.trim()
  // 現 slug と同一なら「変更なし（OK）」扱い。EP は叩かない（BE も no-op 200 を返す）。
  if (current === props.currentSlug) {
    slugStatus.value = 'available'
    slugReason.value = null
    slugCheckSeq++
    return
  }
  if (!current) {
    slugStatus.value = 'invalid'
    return
  }
  if (!isSlugFormatValid(current)) {
    slugStatus.value = 'invalid'
    return
  }
  slugStatus.value = 'checking'
  slugCheckTimer = setTimeout(() => {
    void runSlugCheck(current)
  }, 400)
}

/** 実際に可用性チェック EP を叩き、最新世代の応答だけを反映する。 */
async function runSlugCheck(target: string) {
  const seq = ++slugCheckSeq
  try {
    const res: SlugAvailabilityResponse = await checkSlugAvailable(target)
    if (seq !== slugCheckSeq) return // 古い応答は破棄
    if (res.available) {
      slugStatus.value = 'available'
      slugReason.value = null
    }
    else {
      slugStatus.value = 'unavailable'
      slugReason.value = res.reason ?? 'SLUG_ALREADY_TAKEN'
    }
  }
  catch {
    if (seq !== slugCheckSeq) return
    // 可用性チェック失敗は確定不能。保存は許容し、最終判定は BE 側エラーに委ねる。
    slugStatus.value = 'idle'
    slugReason.value = null
  }
}

/** 保存不可状態か（形式不正・重複・予約語・履歴予約）。変更なし/確認中/未確認は不可。 */
const slugBlocksSave = computed(
  () => slugStatus.value === 'invalid'
    || slugStatus.value === 'unavailable'
    || slugStatus.value === 'checking',
)

/** 保存ボタン無効条件。変更なし・無効 slug・保存中は押せない。 */
const saveDisabled = computed(() => isUnchanged.value || slugBlocksSave.value || saving.value)

/** 可用性メッセージ。 */
const slugMessage = computed<string | null>(() => {
  if (isUnchanged.value) return t('slug.rename.unchanged')
  switch (slugStatus.value) {
    case 'checking':
      return t('slug.checking')
    case 'available':
      return t('slug.available')
    case 'invalid':
      return t('slug.format')
    case 'unavailable':
      return slugReason.value ? t(`slug.reason.${slugReason.value}`) : t('slug.unavailable')
    default:
      return null
  }
})

const messageClass = computed(() => {
  if (isUnchanged.value) return 'text-gray-500'
  switch (slugStatus.value) {
    case 'available':
      return 'text-green-600'
    case 'invalid':
    case 'unavailable':
      return 'text-red-500'
    default:
      return 'text-gray-500'
  }
})

/** リネームを実行する。成功で新 slug を emit（親が遷移）。 */
async function save() {
  const newSlug = slug.value.trim()
  if (isUnchanged.value || slugBlocksSave.value) return
  saving.value = true
  try {
    if (isTeam.value) {
      await renameTeamSlug(props.currentSlug, newSlug)
    }
    else {
      await renameOrganizationSlug(props.currentSlug, newSlug)
    }
    toast.add({ severity: 'success', summary: t('slug.rename.saved'), life: 3000 })
    emit('renamed', newSlug)
  }
  catch (error) {
    // BE エラーコード（422 形式/予約語・409 重複/履歴予約）をフィールドに反映する。
    const fetchError = error as FetchError
    const status = fetchError?.response?.status
    const code = (fetchError?.data as { code?: string } | undefined)?.code
    if (status === 409 && code?.endsWith('063')) {
      // SLUG_RETIRED（履歴予約）
      slugStatus.value = 'unavailable'
      slugReason.value = 'SLUG_RETIRED'
    }
    else if (status === 409) {
      slugStatus.value = 'unavailable'
      slugReason.value = 'SLUG_ALREADY_TAKEN'
    }
    else if (status === 422) {
      slugStatus.value = 'invalid'
    }
    toast.add({ severity: 'error', summary: t('slug.rename.saveError'), life: 5000 })
  }
  finally {
    saving.value = false
  }
}

// 親が currentSlug を差し替えたら入力もリセットする（遷移後の再マウント保険）。
watch(
  () => props.currentSlug,
  (next) => {
    slug.value = next
    slugStatus.value = 'idle'
    slugReason.value = null
  },
)

onBeforeUnmount(() => {
  if (slugCheckTimer) clearTimeout(slugCheckTimer)
})
</script>

<template>
  <div
    class="space-y-3 rounded-lg border border-surface-200 p-6 dark:border-surface-700"
    data-testid="slug-rename-field"
  >
    <div>
      <h2 class="text-lg font-semibold">
        {{ t('slug.rename.title') }}
      </h2>
      <p class="mt-1 text-xs text-surface-500">
        {{ t('slug.rename.help') }}
      </p>
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium" for="slug-rename-input">
        {{ t('slug.label') }}
      </label>
      <InputText
        id="slug-rename-input"
        v-model="slug"
        class="w-full"
        :placeholder="t('slug.placeholder')"
        :class="{ 'p-invalid': slugStatus === 'invalid' || slugStatus === 'unavailable' }"
        data-testid="slug-rename-input"
        @input="onSlugInput"
      />
      <small
        v-if="slugMessage"
        class="mt-1 block"
        :class="messageClass"
        data-testid="slug-rename-message"
      >
        <i
          v-if="slugStatus === 'available' && !isUnchanged"
          class="pi pi-check mr-1"
        />
        <i
          v-else-if="slugStatus === 'invalid' || slugStatus === 'unavailable'"
          class="pi pi-times mr-1"
        />
        {{ slugMessage }}
      </small>
    </div>

    <div class="flex justify-end">
      <Button
        type="button"
        :loading="saving"
        :disabled="saveDisabled"
        :label="t('slug.rename.save')"
        data-testid="slug-rename-save"
        @click="save"
      />
    </div>
  </div>
</template>
