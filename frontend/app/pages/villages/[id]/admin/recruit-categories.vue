<script setup lang="ts">
/**
 * F17.1 P4 — 募集カテゴリ管理画面（`/villages/[id]/admin/recruit-categories`）。
 *
 * 設計書: docs/features/F17.1_village_headman_console_and_recruit_categories.md
 *   §4.2（テーブル・上限20件）/ §5.5（プリセットは可変）/ §6.1（API）/ §7（i18n）/
 *   §9.1（AC-31b, 35, 37, 38）
 *
 * # 金型
 *  CRUD Dialog パターンは `pages/system-admin/village-categories.vue` を踏襲する
 *  （DataTable ＋ 追加/編集 Dialog ＋ 行内の編集/削除ボタン）。
 *  ただし以下は村ドメインの作法に合わせて金型から意図的に変えている:
 *    - 権限: 金型は `authStore.isSystemAdmin` / 本画面は **`useVillageContext().perms.isAdmin`**
 *      （村長 HEADMAN or 長老 ELDER・🔷Q1 御裁可）。`admin-console` ミドルウェアは村に流用不可（§3.2）
 *    - 見出し: 金型は直書き `<header>` / 本画面は **PageHeader**（戻るボタン内蔵・`/統一`）
 *    - 文言: 金型は日本語直書きが残る（「削除しました」等）/ 本画面は **全て i18n**（6言語・AC-37）
 *    - 確認: 金型は `confirm()` / 本画面は Dialog（ブラウザ差異を避ける）
 *
 * # 永続シェル方式（SPA）
 *  村データ・権限は親 `pages/villages/[id].vue` が解決済み。`useVillageContext()` で inject
 *  するのみで村は再フェッチしない。
 *
 * # プリセットは可変（§4.2 / §5.5）
 *  `isPreset` は**由来の記録のみ**で、変更・削除の可否には一切関与させない。
 *  `TodoStatusLabelEntity#assertMutable()`（SYSTEM 既定ラベルは変更不可）は**意図的に不採用**。
 *  非スポーツ村が既定プリセットを消せないなら課題A（スポーツ固着）が解決しないため。
 *  BE 側も同方針（`VillageRecruitCategoryUpdateRequest` の Javadoc に明記）。
 *
 * # カテゴリ名はユーザーデータ（AC-38）
 *  `category.name` / `description` は村長の自由入力であり UI 文言ではない。
 *  **`$t()` を通さず生値で描画する。** i18n の対象は画面の枠（見出し・ボタン・空状態）のみ。
 */
// `composables/village/` は nuxt.config の `imports.dirs` に含まれず自動 import されない
// （`.nuxt/imports.d.ts` の village 系は、後方互換バレル `composables/useVillageApi.ts` の
// 再エクスポート経由で登録されている）。同バレルの冒頭コメントが
// 「新規コードは village/ サブディレクトリの各 composable を直接インポートしてください」と
// 指示しているため、バレルを太らせず明示 import する。
import { useVillageContext } from '~/composables/useVillageContext'
import {
  useVillageRecruitCategoryApi,
  type VillageRecruitCategory,
} from '~/composables/village/useVillageRecruitCategoryApi'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const { t } = useI18n()
const {
  listCategories,
  createCategory,
  updateCategory,
  deleteCategory,
  reorderCategories,
} = useVillageRecruitCategoryApi()
const { showSuccess, showError } = useNotification()

const villageId = computed<string>(() => String(route.params.id))

// 村本体・権限は親シェルから inject（再フェッチしない）
const { village, perms } = useVillageContext()

// =============================================================================
// 定数（BE と揃える）
// =============================================================================

/** BE: VillageRecruitCategoryService.MAX_CATEGORIES_PER_VILLAGE = 20 */
const MAX_CATEGORIES = 20
/** BE: VillageRecruitCategoryCreateRequest#name @Size(max = 40) */
const NAME_MAX = 40
/** BE: VillageRecruitCategoryCreateRequest#description @Size(max = 200) */
const DESCRIPTION_MAX = 200

// =============================================================================
// エラー抽出（join-request.vue / members.vue と同形）
// =============================================================================

interface ApiErrorBody {
  errorCode?: string
  message?: string
  code?: string
}

interface ApiErrorEnvelope {
  data?: ApiErrorBody & { error?: ApiErrorBody }
  status?: number
  statusCode?: number
  response?: { status?: number, _data?: ApiErrorBody & { error?: ApiErrorBody } }
}

/**
 * BE のエラーコードを取り出す。
 *
 * GlobalExceptionHandler は `{"error":{"code":"VILLAGE_086",...}}` の形で返すため、
 * `data.error.code` を最優先で見る（実機で実レスポンスを確認済み）。
 * 旧形式（`errorCode` / `code` 直下）にもフォールバックする。
 */
function extractErrorCode(err: unknown): string | null {
  if (typeof err !== 'object' || err === null) return null
  const e = err as ApiErrorEnvelope
  const body = e.data ?? e.response?._data
  return body?.error?.code ?? body?.errorCode ?? body?.code ?? null
}

/** 募集カテゴリ関連のエラーコードを i18n 文言へ。未知コードは汎用文言に倒す。 */
function translateError(code: string | null, fallback: string): string {
  if (code === 'VILLAGE_083' || code === 'VILLAGE_084' || code === 'VILLAGE_086') {
    return t(`village.recruitCategory.error.${code}`)
  }
  if (code === 'VILLAGE_085') {
    return t('village.recruitCategory.error.VILLAGE_085', { max: MAX_CATEGORIES })
  }
  if (code && code.startsWith('VILLAGE_')) {
    const key = `village.error.${code}`
    const msg = t(key)
    if (msg && msg !== key) return msg
  }
  return fallback
}

// =============================================================================
// 一覧
// =============================================================================

const categories = ref<VillageRecruitCategory[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    categories.value = await listCategories(villageId.value)
  }
  catch (err) {
    categories.value = []
    showError(translateError(extractErrorCode(err), t('village.recruitCategory.error.loadFailed')))
  }
  finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

// 親シェルは村取得をクライアントで行うため、権限確定が本ページのマウント後になりうる。
watch(village, (v) => {
  if (v) void load()
})

// =============================================================================
// 追加・編集 Dialog
// =============================================================================

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const saving = ref(false)

const formName = ref('')
const formDescription = ref('')

/**
 * カラーピッカーの生値。
 *
 * **PrimeVue の ColorPicker（format="hex"）は先頭の `#` を付けない値を emit する**
 * （例: `"e11d48"`）。一方 BE は `@Pattern(regexp = "^#[0-9A-Fa-f]{6}$")` で **`#` 必須**
 * （`VillageRecruitCategoryCreateRequest`）。そのままでは 400 になるため、
 * 送信時に {@link toApiColor} で正規化し、受信時に {@link toPickerColor} で `#` を剥がす。
 * 実機で往復を確認済み。
 */
const formColor = ref<string | null>(null)

/** ピッカー生値 → API 形式（`#RRGGBB`）。未設定なら undefined。 */
function toApiColor(raw: string | null): string | undefined {
  if (!raw) return undefined
  const hex = raw.replace(/^#/, '')
  return /^[0-9A-Fa-f]{6}$/.test(hex) ? `#${hex}` : undefined
}

/** API 形式（`#RRGGBB`）→ ピッカー生値（`#` なし）。 */
function toPickerColor(apiColor: string | null | undefined): string | null {
  if (!apiColor) return null
  return apiColor.replace(/^#/, '')
}

/** 表示用の `#RRGGBB`（プレビュー用）。未設定なら null。 */
const formColorPreview = computed<string | null>(() => toApiColor(formColor.value) ?? null)

const nameError = computed<string | null>(() => {
  const v = formName.value.trim()
  if (!v) return t('village.recruitCategory.validation.nameRequired')
  if (v.length > NAME_MAX) return t('village.recruitCategory.validation.nameMax', { max: NAME_MAX })
  return null
})

const descriptionError = computed<string | null>(() => {
  if (formDescription.value.length > DESCRIPTION_MAX) {
    return t('village.recruitCategory.validation.descriptionMax', { max: DESCRIPTION_MAX })
  }
  return null
})

const canSave = computed(() => !nameError.value && !descriptionError.value && !saving.value)

/** 上限に達しているか（BE の VILLAGE_085 と同じ閾値で先回りして追加ボタンを止める）。 */
const isAtLimit = computed(() => categories.value.length >= MAX_CATEGORIES)

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  formName.value = ''
  formDescription.value = ''
  formColor.value = null
  dialogOpen.value = true
}

function openEdit(cat: VillageRecruitCategory) {
  dialogMode.value = 'edit'
  editingId.value = cat.id ?? null
  formName.value = cat.name ?? ''
  formDescription.value = cat.description ?? ''
  formColor.value = toPickerColor(cat.color)
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
  editingId.value = null
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    // 空文字は「未設定」として null を送る（BE の @Size/@Pattern は null を許容する）。
    const body = {
      name: formName.value.trim(),
      description: formDescription.value.trim() === '' ? undefined : formDescription.value.trim(),
      // ColorPicker の生値は `#` 無しのため API 形式（`#RRGGBB`）へ正規化する。
      color: toApiColor(formColor.value),
    }

    if (dialogMode.value === 'create') {
      await createCategory(villageId.value, body)
      showSuccess(t('village.recruitCategory.created'))
    }
    else if (editingId.value) {
      await updateCategory(villageId.value, editingId.value, body)
      showSuccess(t('village.recruitCategory.updated'))
    }
    closeDialog()
    await load()
  }
  catch (err) {
    // VILLAGE_084（同名重複）/ VILLAGE_085（上限超過）等はここに来る。
    // Dialog は閉じない（入力を保持して直せるようにする）。
    showError(translateError(extractErrorCode(err), t('village.error.generic')))
  }
  finally {
    saving.value = false
  }
}

// =============================================================================
// 削除
// =============================================================================

const deleteDialogOpen = ref(false)
const deleteTarget = ref<VillageRecruitCategory | null>(null)
const deleting = ref(false)

function openDelete(cat: VillageRecruitCategory) {
  deleteTarget.value = cat
  deleteDialogOpen.value = true
}

async function confirmDelete() {
  const target = deleteTarget.value
  if (!target?.id || deleting.value) return
  deleting.value = true
  try {
    await deleteCategory(villageId.value, target.id)
    showSuccess(t('village.recruitCategory.deleted'))
    deleteDialogOpen.value = false
    await load()
  }
  catch (err) {
    // 使用中カテゴリは VILLAGE_086 で弾かれる（AC-10）。症状を隠さずそのまま伝える。
    showError(translateError(extractErrorCode(err), t('village.error.generic')))
    // BE の実状態へ寄せる（別タブで募集が増えた等）
    await load()
  }
  finally {
    deleting.value = false
  }
}

// =============================================================================
// 並び替え（上下移動 → 即 PUT /order）
// =============================================================================

const reordering = ref(false)

async function move(index: number, direction: -1 | 1) {
  const next = index + direction
  if (reordering.value) return
  if (next < 0 || next >= categories.value.length) return

  const reordered = [...categories.value]
  const a = reordered[index]
  const b = reordered[next]
  if (!a || !b) return
  reordered[index] = b
  reordered[next] = a

  const ids = reordered.map(c => c.id).filter((id): id is string => !!id)
  if (ids.length !== reordered.length) return

  // 楽観更新（往復を待たずに並びを反映）。失敗したら BE の実状態へ戻す。
  const previous = categories.value
  categories.value = reordered
  reordering.value = true
  try {
    categories.value = await reorderCategories(villageId.value, ids)
    showSuccess(t('village.recruitCategory.reorderSaved'))
  }
  catch (err) {
    categories.value = previous
    showError(translateError(extractErrorCode(err), t('village.error.generic')))
  }
  finally {
    reordering.value = false
  }
}

// =============================================================================
// 使い方モーダル
// =============================================================================

const showGuide = ref(false)
</script>

<template>
  <div class="mx-auto max-w-4xl p-6">
    <PageHeader
      :title="t('village.recruitCategory.title')"
      size="sm"
      help
      :back-to="`/villages/${villageId}/admin`"
      @help="showGuide = true"
    >
      <template v-if="village" #actions>
        <span class="text-sm text-surface-500">{{ village.name }}</span>
      </template>
    </PageHeader>

    <!-- 権限不足（VILLAGER / VISITOR） -->
    <Message
      v-if="!perms.isAdmin"
      severity="warn"
      :closable="false"
      data-testid="recruit-category-access-denied"
    >
      {{ t('village.admin.accessDenied') }}
    </Message>

    <template v-else>
      <div class="mb-4 flex items-start justify-between gap-3">
        <div>
          <p class="text-sm text-surface-600 dark:text-surface-300">
            {{ t('village.recruitCategory.subtitle') }}
          </p>
          <p class="mt-1 text-xs text-surface-500">
            {{ t('village.recruitCategory.limitNote', { max: MAX_CATEGORIES, count: categories.length }) }}
          </p>
        </div>
        <Button
          :label="t('village.recruitCategory.add')"
          icon="pi pi-plus"
          :disabled="isAtLimit || loading"
          data-testid="recruit-category-add"
          @click="openCreate"
        />
      </div>

      <SectionCard>
        <div v-if="loading" class="py-12 text-center text-surface-500">
          <i class="pi pi-spin pi-spinner text-2xl" aria-hidden="true" />
        </div>

        <DataTable
          v-else
          :value="categories"
          data-key="id"
          striped-rows
          class="text-sm"
          data-testid="recruit-category-table"
        >
          <template #empty>
            <div class="flex flex-col items-center justify-center gap-3 py-12 text-surface-400">
              <i class="pi pi-tags text-4xl" aria-hidden="true" />
              <p class="text-sm">
                {{ t('village.recruitCategory.empty') }}
              </p>
            </div>
          </template>

          <!-- 並び替え -->
          <Column :header="t('village.recruitCategory.reorder')" style="width: 6.5rem">
            <template #body="{ index }: { index: number }">
              <div class="flex items-center gap-1">
                <Button
                  icon="pi pi-chevron-up"
                  text
                  rounded
                  size="small"
                  :aria-label="t('village.recruitCategory.moveUp')"
                  :disabled="index === 0 || reordering"
                  :data-testid="`recruit-category-up-${index}`"
                  @click="move(index, -1)"
                />
                <Button
                  icon="pi pi-chevron-down"
                  text
                  rounded
                  size="small"
                  :aria-label="t('village.recruitCategory.moveDown')"
                  :disabled="index === categories.length - 1 || reordering"
                  :data-testid="`recruit-category-down-${index}`"
                  @click="move(index, 1)"
                />
              </div>
            </template>
          </Column>

          <!-- カテゴリ名（ユーザーデータ: $t() を通さず生値で描画・AC-38） -->
          <Column :header="t('village.recruitCategory.name')" style="min-width: 14rem">
            <template #body="{ data: row }: { data: VillageRecruitCategory }">
              <div class="flex items-center gap-2">
                <span
                  v-if="row.color"
                  class="inline-block h-3 w-3 shrink-0 rounded-full border border-surface-300"
                  :style="{ backgroundColor: row.color }"
                  aria-hidden="true"
                />
                <span class="font-medium text-surface-700 dark:text-surface-200">{{ row.name }}</span>
                <Tag
                  v-if="row.isPreset"
                  :value="t('village.recruitCategory.preset')"
                  severity="secondary"
                />
              </div>
              <p v-if="row.description" class="mt-1 text-xs text-surface-500">
                {{ row.description }}
              </p>
            </template>
          </Column>

          <!-- 使用中件数（削除可否の判断材料。削除ガードと同じ集計・§6.2） -->
          <Column :header="t('village.recruitCategory.usage')" style="width: 9rem">
            <template #body="{ data: row }: { data: VillageRecruitCategory }">
              <span
                :class="(row.recruitCount ?? 0) > 0 ? 'font-medium text-surface-700 dark:text-surface-200' : 'text-surface-400'"
              >
                {{ t('village.recruitCategory.usageCount', { count: row.recruitCount ?? 0 }) }}
              </span>
            </template>
          </Column>

          <!-- 操作 -->
          <Column style="width: 7rem">
            <template #body="{ data: row }: { data: VillageRecruitCategory }">
              <div class="flex items-center gap-1">
                <Button
                  icon="pi pi-pencil"
                  text
                  rounded
                  size="small"
                  severity="secondary"
                  :aria-label="t('village.recruitCategory.edit')"
                  :data-testid="`recruit-category-edit-${row.id}`"
                  @click="openEdit(row)"
                />
                <!--
                  ラベルは common の `button.delete`（「削除」）を使う。
                  village.action.delete は **「村を削除」** であり、カテゴリの削除ボタンに
                  当てると「村ごと消える」と誤読させる（実機のスクリーンショットで発見）。
                -->
                <Button
                  icon="pi pi-trash"
                  text
                  rounded
                  size="small"
                  severity="danger"
                  :aria-label="t('button.delete')"
                  :data-testid="`recruit-category-delete-${row.id}`"
                  @click="openDelete(row)"
                />
              </div>
            </template>
          </Column>
        </DataTable>
      </SectionCard>
    </template>

    <!-- 追加・編集 Dialog（金型 system-admin/village-categories.vue の CRUD Dialog パターン） -->
    <Dialog
      v-model:visible="dialogOpen"
      modal
      :header="dialogMode === 'create' ? t('village.recruitCategory.add') : t('village.recruitCategory.edit')"
      :style="{ width: '32rem' }"
      :draggable="false"
      data-testid="recruit-category-dialog"
      @hide="closeDialog"
    >
      <div class="flex flex-col gap-4">
        <!-- カテゴリ名 -->
        <div>
          <label for="recruit-category-name" class="mb-1 block text-sm font-medium">
            {{ t('village.recruitCategory.name') }}
            <span class="text-red-600">*</span>
          </label>
          <InputText
            id="recruit-category-name"
            v-model="formName"
            :maxlength="NAME_MAX"
            :placeholder="t('village.recruitCategory.namePlaceholder')"
            :invalid="!!nameError && formName.length > 0"
            class="w-full"
            data-testid="recruit-category-name-input"
          />
          <p v-if="nameError && formName.length > 0" class="mt-1 text-xs text-red-600">
            {{ nameError }}
          </p>
        </div>

        <!-- 説明 -->
        <div>
          <label for="recruit-category-description" class="mb-1 block text-sm font-medium">
            {{ t('village.recruitCategory.description') }}
          </label>
          <Textarea
            id="recruit-category-description"
            v-model="formDescription"
            :maxlength="DESCRIPTION_MAX"
            :placeholder="t('village.recruitCategory.descriptionPlaceholder')"
            rows="3"
            class="w-full"
            :invalid="!!descriptionError"
            data-testid="recruit-category-description-input"
          />
          <p v-if="descriptionError" class="mt-1 text-xs text-red-600">
            {{ descriptionError }}
          </p>
        </div>

        <!-- 色 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('village.recruitCategory.color') }}
          </label>
          <div class="flex items-center gap-2">
            <ColorPicker v-model="formColor" format="hex" data-testid="recruit-category-color" />
            <span class="text-xs text-surface-500">{{ formColorPreview ?? '—' }}</span>
            <!-- 「キャンセル」ではなく「色を外す」（この操作は色の解除であって取り消しではない）。 -->
            <Button
              v-if="formColor"
              :label="t('village.recruitCategory.clearColor')"
              text
              size="small"
              severity="secondary"
              @click="formColor = null"
            />
          </div>
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          :disabled="saving"
          @click="closeDialog"
        />
        <Button
          :label="t('village.action.save')"
          :disabled="!canSave"
          :loading="saving"
          data-testid="recruit-category-save"
          @click="save"
        />
      </template>
    </Dialog>

    <!-- 削除確認 Dialog（金型は confirm() だが、ブラウザ差異を避け Dialog にする） -->
    <Dialog
      v-model:visible="deleteDialogOpen"
      modal
      :header="t('village.recruitCategory.confirmDelete')"
      :style="{ width: '28rem' }"
      :draggable="false"
      data-testid="recruit-category-delete-dialog"
    >
      <p v-if="deleteTarget" class="text-sm">
        {{ t('village.recruitCategory.confirmDeleteBody', { name: deleteTarget.name ?? '' }) }}
      </p>
      <template #footer>
        <Button
          :label="t('village.action.cancel')"
          severity="secondary"
          text
          :disabled="deleting"
          @click="deleteDialogOpen = false"
        />
        <!-- 「村を削除」ではなく「削除」（上記の注参照）。 -->
        <Button
          :label="t('button.delete')"
          severity="danger"
          :loading="deleting"
          data-testid="recruit-category-delete-confirm"
          @click="confirmDelete"
        />
      </template>
    </Dialog>

    <!-- 使い方モーダル -->
    <VillageRecruitCategoryGuideModal v-model:visible="showGuide" />
  </div>
</template>
