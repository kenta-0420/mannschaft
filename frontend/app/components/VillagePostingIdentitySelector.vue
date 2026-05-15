<script setup lang="ts">
/**
 * F17.1 村機能 — 投稿主体切替 Selector コンポーネント
 *
 * 村内で投稿する際の「投稿主体（個人 / チーム代表 / 組織代表）」を選択する。
 *
 * 設計書: docs/features/F17.1_village_community.md
 *   §4.6  投稿主体一覧 API  (GET /api/v1/me/villages/{villageId}/posting-identities)
 *   §4.8  投稿主体エントリ DTO (PostingIdentityResponse)
 *   §5.4  投稿主体代表権限の検証
 *
 * Phase 1 仕様:
 *   - `visible=false` がデフォルト。非表示時は何も描画しない。
 *   - 内部ロジック（API 取得・USER 既定選択・v-model 同期）は完成させており、
 *     Phase 2 で `visible=true` に切替えれば即動作する設計。
 *   - 既存 Service への統合は別軍議のため、ここでは UI 部品として配置するのみ。
 *
 * 厳守:
 *   - any 禁止 / 文字列直書き禁止 / 既存ファイル変更禁止
 *   - 型・composable・i18n は FE1 が用意したものを使用
 */
import type {
  PostingIdentityResponse,
  VillageSubjectType,
} from '~/types/village'

// =============================================================================
// Props / Emits
// =============================================================================

/** v-model の値型（subjectType + subjectId のペア） */
export interface PostingIdentitySelection {
  subjectType: VillageSubjectType
  subjectId: number
}

const props = withDefaults(
  defineProps<{
    /** 対象の村 ID（UUIDv7 文字列） */
    villageId: string
    /** v-model の値。Phase 1 では USER 固定で初期化される */
    modelValue?: PostingIdentitySelection | null
    /**
     * 表示フラグ。Phase 1 では既定 false（非表示）。
     * Phase 2 で true に切替えれば即時 UI 表示される。
     */
    visible?: boolean
  }>(),
  {
    modelValue: null,
    visible: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: PostingIdentitySelection]
}>()

// =============================================================================
// i18n
// =============================================================================
const { t } = useI18n()

// =============================================================================
// 状態 — 投稿主体一覧 (§4.6)
// =============================================================================
const identities = ref<PostingIdentityResponse[]>([])
const loading = ref(false)
const errorMessage = ref<string | null>(null)

const api = useVillageApi()

/**
 * 投稿主体一覧を取得し、USER（個人）を既定選択にする。
 *
 * Phase 1 では `visible=false` でも内部ロジック確認のため取得を行う方針も
 * 取り得るが、無駄な API 呼び出しを避けるため `visible=true` のときのみ実行する。
 * （Phase 2 での切替 watcher により、`visible` が true になった瞬間に取得される）
 */
async function loadIdentities() {
  loading.value = true
  errorMessage.value = null
  try {
    const res = await api.listPostingIdentities(props.villageId)
    identities.value = res.identities ?? []
    // USER（個人）を既定選択に
    const userIdentity = identities.value.find(i => i.subjectType === 'USER')
    if (userIdentity && !props.modelValue) {
      emit('update:modelValue', {
        subjectType: userIdentity.subjectType,
        subjectId: userIdentity.subjectId,
      })
    }
  }
  catch (e) {
    // 障害対応の原則: 症状を隠さず正直にエラーメッセージを保持する
    errorMessage.value = e instanceof Error ? e.message : String(e)
  }
  finally {
    loading.value = false
  }
}

// =============================================================================
// 表示制御 — Phase 1 は visible=false 既定で何も描画しない
// =============================================================================
// 表示開始（false → true）時に取得を行う。Phase 1 では発火しない。
watch(
  () => props.visible,
  (now) => {
    if (now) {
      loadIdentities()
    }
  },
  { immediate: true },
)

// 村が切り替わったときは再取得（visible 中のみ）
watch(
  () => props.villageId,
  () => {
    if (props.visible) {
      loadIdentities()
    }
  },
)

// =============================================================================
// Select 用オプションモデル
// =============================================================================
interface IdentityOption {
  /** 一意キー — `${subjectType}:${subjectId}` */
  value: string
  /** 表示ラベル（i18n 済） */
  label: string
  /** v-model 連動用の元データ */
  subjectType: VillageSubjectType
  subjectId: number
}

/**
 * 主体種別ごとに i18n キーを引いてラベルを組み立てる。
 * 例: 「個人として投稿: 山田太郎」
 */
function buildLabel(item: PostingIdentityResponse): string {
  const prefixKey
    = item.subjectType === 'USER'
      ? 'village.postingIdentity.asUser'
      : item.subjectType === 'TEAM'
        ? 'village.postingIdentity.asTeam'
        : 'village.postingIdentity.asOrganization'
  return `${t(prefixKey)}: ${item.displayName}`
}

const options = computed<IdentityOption[]>(() =>
  identities.value
    // USER は常に表示、TEAM/ORGANIZATION は canPostAs=true のみ
    .filter(i => i.subjectType === 'USER' || i.canPostAs)
    .map(i => ({
      value: `${i.subjectType}:${i.subjectId}`,
      label: buildLabel(i),
      subjectType: i.subjectType,
      subjectId: i.subjectId,
    })),
)

/** Select に渡す現在値（IdentityOption.value） */
const selectedValue = computed<string | null>(() => {
  if (!props.modelValue) return null
  return `${props.modelValue.subjectType}:${props.modelValue.subjectId}`
})

function onChange(newValue: string) {
  const found = options.value.find(o => o.value === newValue)
  if (!found) return
  emit('update:modelValue', {
    subjectType: found.subjectType,
    subjectId: found.subjectId,
  })
}
</script>

<template>
  <!--
    Phase 1: visible=false の場合は完全に非描画。
    Phase 2 で visible=true にすれば、即座に Select が表示される。
  -->
  <div
    v-if="visible"
    class="village-posting-identity-selector flex flex-col gap-1"
  >
    <label
      :for="`village-posting-identity-${villageId}`"
      class="text-xs sm:text-sm font-medium text-surface-700 dark:text-surface-200"
    >
      {{ t('village.postingIdentity.title') }}
    </label>

    <Select
      :id="`village-posting-identity-${villageId}`"
      :model-value="selectedValue"
      :options="options"
      option-label="label"
      option-value="value"
      :loading="loading"
      :disabled="loading || options.length === 0"
      :placeholder="t('village.postingIdentity.title')"
      class="w-full"
      @update:model-value="onChange"
    />

    <small
      v-if="errorMessage"
      class="text-red-600 dark:text-red-400"
    >
      {{ errorMessage }}
    </small>
  </div>
</template>
