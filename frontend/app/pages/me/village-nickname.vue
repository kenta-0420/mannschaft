<script setup lang="ts">
/**
 * F17.1 村機能 Phase 1-FE — 村ニックネーム編集ページ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.7
 *
 * Phase 1 は「全村共通 1 つ」運用。/api/v1/me/village-nickname に対し
 * GET/PUT で操作する（村ID を取らない）。プラットフォーム全体でニックネームは
 * UNIQUE。月3回までしか変更できないレートリミット付き。
 *
 * エラーコード（バックエンド VillageErrorCode 準拠）:
 *   - VILLAGE_008 (409 NICKNAME_TAKEN): ニックネーム重複
 *   - VILLAGE_011 (429 NICKNAME_CHANGE_THROTTLED): 月3回超過
 *   - VILLAGE_028 (422 NICKNAME_INVALID): 長さ・NG ワード・使用文字違反
 */
import type {
  VillageNicknameResponse,
  VillageNicknameUpdateRequest,
} from '~/types/village'

definePageMeta({
  layout: 'default',
  middleware: 'auth',
})

const { t } = useI18n()
const villageApi = useVillageApi()
const notification = useNotification()
const { captureQuiet } = useErrorReport()

const loading = ref(true)
const saving = ref(false)

// 現在のニックネーム情報（未設定なら null）
const current = ref<VillageNicknameResponse | null>(null)

// 編集フォーム
const form = ref({
  nickname: '',
  avatarR2Key: '',
  bio: '',
})

/**
 * 月内変更回数の残り計算。
 * 未設定（current=null）の場合は monthlyLimit を満額として扱う。
 */
const remainingChanges = computed<number>(() => {
  if (!current.value) return 3
  return Math.max(0, current.value.monthlyLimit - current.value.changeCountThisMonth)
})

/**
 * 保存ボタンの活性判定。
 *   - 必須項目（nickname）が 2〜40 文字
 *   - 残り変更回数があるか、または初回設定（current=null）
 */
const canSave = computed<boolean>(() => {
  const n = form.value.nickname.trim()
  if (n.length < 2 || n.length > 40) return false
  if (form.value.bio.length > 500) return false
  if (form.value.avatarR2Key.length > 255) return false
  // 既に設定済みでレート上限ならボタン非活性
  if (current.value && remainingChanges.value <= 0) return false
  return true
})

/** 初期ロード — 404 はニックネーム未設定として正常扱い */
async function load() {
  loading.value = true
  try {
    const res = await villageApi.getMyNickname()
    current.value = res
    form.value = {
      nickname: res.nickname,
      avatarR2Key: res.avatarR2Key ?? '',
      bio: res.bio ?? '',
    }
  } catch (err) {
    const status = (err as { statusCode?: number }).statusCode
    if (status === 404) {
      // ニックネーム未設定 — フォームは空のまま開始
      current.value = null
      form.value = { nickname: '', avatarR2Key: '', bio: '' }
    } else {
      captureQuiet(err, { context: 'VillageNickname: 取得失敗' })
      notification.error(t('village.error.generic'))
    }
  } finally {
    loading.value = false
  }
}

/** バックエンドの構造化エラーから errorCode を取り出す */
function extractErrorCode(err: unknown): string | null {
  const data = (err as { data?: { errorCode?: string } }).data
  return data?.errorCode ?? null
}

/** エラーを i18n キーに紐付けてトースト表示 */
function handleSaveError(err: unknown) {
  const code = extractErrorCode(err)
  if (code === 'VILLAGE_008') {
    notification.error(t('village.error.VILLAGE_008'))
    return
  }
  if (code === 'VILLAGE_011') {
    notification.error(t('village.error.VILLAGE_011'))
    return
  }
  if (code === 'VILLAGE_028') {
    notification.error(t('village.error.VILLAGE_028'))
    return
  }
  // 上記以外は generic + ログ送信
  captureQuiet(err, { context: 'VillageNickname: 保存失敗' })
  notification.error(t('village.error.generic'))
}

/** 保存 — 成功時は再ロードして月内変更回数を最新化 */
async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const body: VillageNicknameUpdateRequest = {
      nickname: form.value.nickname.trim(),
      avatarR2Key: form.value.avatarR2Key.trim() === '' ? null : form.value.avatarR2Key.trim(),
      bio: form.value.bio.trim() === '' ? null : form.value.bio.trim(),
    }
    await villageApi.updateNickname(body)
    notification.success(t('village.nickname.saveSuccess'))
    await load()
  } catch (err) {
    handleSaveError(err)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  load()
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('village.nickname.title')" back-to="/settings" />

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-6">
      <SectionCard :title="t('village.nickname.title')">
        <div class="space-y-4">
          <!-- 説明 -->
          <p class="text-sm text-surface-500">
            {{ t('village.subtitle') }}
          </p>

          <!-- 月内変更回数 -->
          <div class="flex items-center gap-2">
            <Tag
              :value="t('village.nickname.changeLimit', { limit: current?.monthlyLimit ?? 3 })"
              severity="info"
            />
            <Tag
              :value="t('village.nickname.remaining', { count: remainingChanges })"
              :severity="remainingChanges <= 0 ? 'danger' : 'secondary'"
            />
          </div>

          <!-- ニックネーム -->
          <div>
            <label class="mb-1 block text-sm font-medium" for="village-nickname-input">
              {{ t('village.nickname.title') }}
              <span class="text-red-500">*</span>
            </label>
            <InputText
              id="village-nickname-input"
              v-model="form.nickname"
              class="w-full"
              :placeholder="t('village.nickname.placeholder')"
              maxlength="40"
            />
            <p class="mt-1 text-xs text-surface-500">
              {{ form.nickname.trim().length }} / 40
            </p>
          </div>

          <!-- アバター R2 キー -->
          <div>
            <label class="mb-1 block text-sm font-medium" for="village-avatar-input">
              {{ t('village.nickname.avatar') }}
            </label>
            <InputText
              id="village-avatar-input"
              v-model="form.avatarR2Key"
              class="w-full"
              placeholder="village_user_nicknames/global/..."
              maxlength="255"
            />
          </div>

          <!-- 自己紹介 -->
          <div>
            <label class="mb-1 block text-sm font-medium" for="village-bio-input">
              {{ t('village.nickname.bio') }}
            </label>
            <Textarea
              id="village-bio-input"
              v-model="form.bio"
              class="w-full"
              rows="4"
              maxlength="500"
            />
            <p class="mt-1 text-xs text-surface-500">
              {{ form.bio.length }} / 500
            </p>
          </div>

          <!-- 保存 -->
          <div class="flex justify-end">
            <Button
              :label="t('village.action.save')"
              icon="pi pi-check"
              :loading="saving"
              :disabled="!canSave"
              @click="save"
            />
          </div>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
