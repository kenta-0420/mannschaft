<script setup lang="ts">
/**
 * F17.1 村機能 Phase 1-FE — お気に入りピン編集ページ
 *
 * 設計書: docs/features/F17.1_village_community.md §4.8 / §4.9
 *
 * 機能:
 *   - 自分のお気に入り村ピン一覧を `/api/v1/me/village-pins` から取得
 *   - 上下ボタンで並び替え（Phase 2 でドラッグソート対応予定）
 *   - 並び替え後は `PATCH /api/v1/me/village-pins/order` で永続化
 *     楽観的に UI を更新し、API 失敗時にロールバック
 *   - 「外す」ボタンで確認ダイアログ → `DELETE /api/v1/me/village-pins/{id}`
 *   - 村クリックで `/villages/{villageId}` へ遷移
 *
 * 関連エラーコード:
 *   - VILLAGE_013: お気に入り村の上限（30件）を超えた
 *   - VILLAGE_044: お気に入り村のピンが見つからない
 */
import type { PinResponse } from '~/types/village'

definePageMeta({
  layout: 'default',
  middleware: 'auth',
})

const { t } = useI18n()
const villageApi = useVillageApi()
const notification = useNotification()
const confirmDialog = useConfirm()
const router = useRouter()
const { captureQuiet } = useErrorReport()

const loading = ref(true)
const reordering = ref(false)
const removingVillageId = ref<string | null>(null)

const pins = ref<PinResponse[]>([])
const maxLimit = ref(30)

/** 初期ロード */
async function load() {
  loading.value = true
  try {
    const res = await villageApi.listPins()
    pins.value = [...res.items].sort((a, b) => a.sortOrder - b.sortOrder)
    maxLimit.value = res.maxLimit
  } catch (err) {
    captureQuiet(err, { context: 'VillagePins: 一覧取得失敗' })
    notification.error(t('village.error.generic'))
  } finally {
    loading.value = false
  }
}

/** バックエンドの構造化エラーから errorCode を取り出す */
function extractErrorCode(err: unknown): string | null {
  const data = (err as { data?: { errorCode?: string } }).data
  return data?.errorCode ?? null
}

/** 解除エラーの i18n マッピング */
function handleUnpinError(err: unknown) {
  const code = extractErrorCode(err)
  if (code === 'VILLAGE_044') {
    notification.error(t('village.error.VILLAGE_044'))
    return
  }
  captureQuiet(err, { context: 'VillagePins: 解除失敗' })
  notification.error(t('village.error.generic'))
}

/** 並び替えエラーの i18n マッピング */
function handleReorderError(err: unknown) {
  const code = extractErrorCode(err)
  if (code === 'VILLAGE_044') {
    notification.error(t('village.error.VILLAGE_044'))
    return
  }
  captureQuiet(err, { context: 'VillagePins: 並び替え失敗' })
  notification.error(t('village.error.generic'))
}

/**
 * 並び順を API に保存。
 * 楽観的に呼び出し元で配列を入れ替え済みである前提。失敗時は previous で巻き戻す。
 */
async function persistOrder(previous: PinResponse[]) {
  reordering.value = true
  try {
    const orderedVillageIds = pins.value.map((p) => p.villageId)
    const res = await villageApi.updatePinOrder({ orderedVillageIds })
    // バックエンドが返した sortOrder で再同期（楽観的更新と乖離している場合に備える）
    pins.value = [...res.items].sort((a, b) => a.sortOrder - b.sortOrder)
    notification.success(t('village.pin.reorderSuccess'))
  } catch (err) {
    // ロールバック
    pins.value = previous
    handleReorderError(err)
  } finally {
    reordering.value = false
  }
}

/** 1 つ上と入れ替え */
async function moveUp(index: number) {
  if (index <= 0 || reordering.value) return
  const previous = [...pins.value]
  const next = [...pins.value]
  ;[next[index - 1], next[index]] = [next[index], next[index - 1]] as [PinResponse, PinResponse]
  pins.value = next
  await persistOrder(previous)
}

/** 1 つ下と入れ替え */
async function moveDown(index: number) {
  if (index >= pins.value.length - 1 || reordering.value) return
  const previous = [...pins.value]
  const next = [...pins.value]
  ;[next[index], next[index + 1]] = [next[index + 1], next[index]] as [PinResponse, PinResponse]
  pins.value = next
  await persistOrder(previous)
}

/** ピン解除（確認ダイアログ経由） */
function confirmUnpin(pin: PinResponse) {
  confirmDialog.require({
    message: pin.villageName,
    header: t('village.action.unpin'),
    icon: 'pi pi-exclamation-triangle',
    acceptClass: 'p-button-danger',
    acceptLabel: t('village.action.unpin'),
    rejectLabel: t('village.action.cancel'),
    accept: async () => {
      await unpin(pin.villageId)
    },
  })
}

/** ピン解除実行 */
async function unpin(villageId: string) {
  removingVillageId.value = villageId
  try {
    await villageApi.removePin(villageId)
    notification.success(t('village.success.unpinned'))
    await load()
  } catch (err) {
    handleUnpinError(err)
  } finally {
    removingVillageId.value = null
  }
}

/** 村詳細へ遷移 */
function gotoVillage(villageId: string) {
  router.push(`/villages/${villageId}`)
}

onMounted(() => {
  load()
})
</script>

<template>
  <div class="mx-auto max-w-3xl">
    <PageHeader :title="t('village.pin.title')" back-to="/dashboard" />

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-6">
      <SectionCard :title="t('village.pin.title')">
        <div class="space-y-4">
          <!-- 説明 + 件数表示 -->
          <div class="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <p class="text-sm text-surface-500">
              {{ t('village.pin.limit', { max: maxLimit }) }}
            </p>
            <Tag
              :value="`${pins.length} / ${maxLimit}`"
              :severity="pins.length >= maxLimit ? 'warn' : 'info'"
            />
          </div>

          <!-- 空状態 -->
          <div
            v-if="pins.length === 0"
            class="flex flex-col items-center gap-3 py-8 text-center"
          >
            <i class="pi pi-bookmark text-4xl text-surface-300" />
            <p class="text-surface-500">
              {{ t('village.pin.empty') }}
            </p>
            <Button
              :label="t('village.title')"
              icon="pi pi-search"
              severity="secondary"
              outlined
              @click="router.push('/villages')"
            />
          </div>

          <!-- ピン一覧 -->
          <ul v-else class="space-y-2">
            <li
              v-for="(pin, index) in pins"
              :key="pin.id"
              class="flex items-center gap-3 rounded-md border border-surface-200 bg-white p-3 transition-colors hover:bg-surface-50 dark:border-surface-700 dark:bg-surface-900 dark:hover:bg-surface-800"
            >
              <!-- 村アイコン + 名前（クリックで遷移） -->
              <button
                type="button"
                class="flex flex-1 items-center gap-3 text-left"
                @click="gotoVillage(pin.villageId)"
              >
                <img
                  v-if="pin.villageIconUrl"
                  :src="pin.villageIconUrl"
                  :alt="pin.villageName"
                  class="h-10 w-10 rounded-full object-cover"
                >
                <div
                  v-else
                  class="flex h-10 w-10 items-center justify-center rounded-full bg-surface-100 dark:bg-surface-800"
                >
                  <i class="pi pi-home text-surface-400" />
                </div>
                <span class="font-medium">{{ pin.villageName }}</span>
              </button>

              <!-- 並び替えボタン -->
              <div class="flex items-center gap-1">
                <Button
                  icon="pi pi-arrow-up"
                  text
                  rounded
                  size="small"
                  :disabled="index === 0 || reordering"
                  :aria-label="t('village.pin.reorder')"
                  @click="moveUp(index)"
                />
                <Button
                  icon="pi pi-arrow-down"
                  text
                  rounded
                  size="small"
                  :disabled="index === pins.length - 1 || reordering"
                  :aria-label="t('village.pin.reorder')"
                  @click="moveDown(index)"
                />
              </div>

              <!-- 解除ボタン -->
              <Button
                icon="pi pi-times"
                text
                rounded
                size="small"
                severity="danger"
                :loading="removingVillageId === pin.villageId"
                :aria-label="t('village.action.unpin')"
                @click="confirmUnpin(pin)"
              />
            </li>
          </ul>
        </div>
      </SectionCard>
    </div>

    <ConfirmDialog />
  </div>
</template>
