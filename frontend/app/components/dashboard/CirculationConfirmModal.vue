<script setup lang="ts">
import type { CirculationActionItem, ScopeTabType } from '~/types/dashboard-scope'
import type { ElectronicSeal } from '~/types/seal'

/**
 * 要対応ウィジェット — 回覧板確認モーダル。
 *
 * 回覧板アイテムをクリックしたときにページ遷移せず、このモーダルで押印できる。
 * - 印鑑あり → 「確認しました」ボタンで押印・モーダル閉じ
 * - 印鑑なし → 設定ページへのリンクを表示
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 */
const props = defineProps<{
  visible: boolean
  item: CirculationActionItem
  scopeType: ScopeTabType
  scopeId: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirmed: []
}>()

const { t } = useI18n()
const { showError, showSuccess } = useNotification()
const authStore = useAuthStore()
const { getSeals } = useSealApi()
const { stampDocument } = useCirculationApi()

const userId = computed(() => authStore.currentUser?.id ?? null)
const seals = ref<ElectronicSeal[]>([])
const loadingSeals = ref(false)
const stamping = ref(false)

/** 印鑑が1つ以上あるか */
const hasSeal = computed(() => seals.value.length > 0)

/** デフォルトで使用する印鑑（最初の1件） */
const defaultSeal = computed(() => seals.value[0] ?? null)

function close() {
  emit('update:visible', false)
}

async function loadSeals() {
  if (userId.value === null) return
  loadingSeals.value = true
  try {
    seals.value = await getSeals(userId.value)
  } catch {
    seals.value = []
  } finally {
    loadingSeals.value = false
  }
}

async function onConfirm() {
  if (!defaultSeal.value) return
  stamping.value = true
  try {
    const documentId = Number(props.item.id)
    await stampDocument(documentId, {
      sealId: defaultSeal.value.sealId,
      sealVariant: defaultSeal.value.variant,
    })
    showSuccess(t('swipeWidgets.actionRequired.circulationModal.stampSuccess'))
    emit('confirmed')
    close()
  } catch {
    showError(t('swipeWidgets.actionRequired.circulationModal.stampError'))
  } finally {
    stamping.value = false
  }
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '-'
  const d = new Date(dateStr)
  return d.toLocaleDateString()
}

// immediate: true が必須。利用側（ScopeActionRequiredWidget / WidgetCommandCenter）は
// v-if="item" と visible=true を同時にセットするため、初回マウント時に visible の
// false→true 遷移が発生せず、immediate なしでは watch が不発になり印鑑ロードが走らない
// （印鑑登録済みでも「印鑑が設定されていません」誤表示・実機E2Eで捕捉）。
// visible=false でマウントされた場合は if (val) ガードによりロードしない。
watch(
  () => props.visible,
  (val) => {
    if (val) {
      loadSeals()
    }
  },
  { immediate: true },
)
</script>

<template>
  <Dialog
    :visible="visible"
    :header="$t('swipeWidgets.actionRequired.circulationModal.title')"
    modal
    class="w-full max-w-md"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-3">
      <div>
        <p class="text-sm font-semibold text-surface-900 dark:text-surface-0">
          {{ item.title }}
        </p>
      </div>

      <div class="flex flex-col gap-1 text-xs text-surface-500">
        <span>
          <i class="pi pi-calendar mr-1" />
          {{ $t('swipeWidgets.actionRequired.circulationModal.circulatedAt') }}:
          {{ formatDate(item.circulatedAt) }}
        </span>
        <span v-if="item.deadline">
          <i class="pi pi-clock mr-1" />
          {{ $t('swipeWidgets.actionRequired.circulationModal.deadline') }}:
          {{ formatDate(item.deadline) }}
        </span>
      </div>

      <div v-if="loadingSeals" class="text-center py-2">
        <i class="pi pi-spin pi-spinner text-surface-400" />
      </div>

      <div v-else-if="!hasSeal" class="rounded border border-amber-200 bg-amber-50 dark:bg-amber-900/20 p-3 text-sm">
        <p class="text-amber-700 dark:text-amber-300">
          {{ $t('swipeWidgets.actionRequired.circulationModal.noSealMessage') }}
        </p>
        <NuxtLink
          to="/settings/seal"
          class="mt-2 inline-flex items-center text-primary hover:underline text-xs"
          @click="close"
        >
          <i class="pi pi-arrow-right mr-1" />
          {{ $t('swipeWidgets.actionRequired.circulationModal.goToSealSettings') }}
        </NuxtLink>
      </div>
    </div>

    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        @click="close"
      />
      <Button
        v-if="hasSeal"
        :label="$t('swipeWidgets.actionRequired.circulationModal.confirm')"
        :loading="stamping"
        icon="pi pi-stamp"
        @click="onConfirm"
      />
    </template>
  </Dialog>
</template>
