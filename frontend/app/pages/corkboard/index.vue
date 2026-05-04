<script setup lang="ts">
/**
 * F09.8.1 Phase 5: コルクボード一覧（既存）にピン止め関連の動線を追加。
 *
 * 既存 corkboard index 画面はボード一覧のみで、個別カードは描画されない。
 * 個別カードへの 📌 ボタン追加は将来のボード詳細ページ実装時に行うものとし、
 * 本 Phase ではボード一覧から「ピン止め一覧（マイコルクボード）」への
 * 動線確保（ヘッダーリンク）と、個人ボードカードへの「ピン止め可能」
 * ヒント表示にとどめる（設計書 §6.2 の意図に対する最小限の現実解）。
 */
import type { CorkboardResponse } from '~/types/corkboard'

definePageMeta({ middleware: 'auth' })
const { t } = useI18n()
const { getMyBoards } = useCorkboardApi()
const { showError } = useNotification()
const boards = ref<CorkboardResponse[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await getMyBoards()
    boards.value = res.data
  } catch {
    showError('コルクボードの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>
<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <h1 class="text-2xl font-bold">コルクボード</h1>
      <div class="flex items-center gap-2">
        <NuxtLink to="/my/corkboard">
          <Button
            :label="t('corkboard.pageTitle')"
            icon="pi pi-bookmark-fill"
            size="small"
            severity="secondary"
            text
          />
        </NuxtLink>
        <Button label="ボードを作成" icon="pi pi-plus" />
      </div>
    </div>
    <PageLoading v-if="loading" size="40px" />
    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="b in boards"
        :key="b.id"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4"
        :style="b.backgroundColor ? `border-color: ${b.backgroundColor}40` : ''"
      >
        <div class="flex items-start justify-between gap-2">
          <h3 class="text-sm font-semibold">{{ b.title }}</h3>
          <!-- F09.8.1 Phase 5: 個人ボードのみ「ピン止め可能」マークを表示 -->
          <span
            v-if="b.scopeType === 'PERSONAL'"
            class="inline-flex items-center gap-0.5 text-[10px] text-surface-400"
            :title="t('corkboard.addNewPinHint')"
            :aria-label="t('corkboard.addNewPinHint')"
          >
            <i class="pi pi-bookmark text-[10px]" />
            {{ t('corkboard.pin') }}
          </span>
        </div>
        <p v-if="b.description" class="mt-1 text-xs text-surface-400">{{ b.description }}</p>
        <p class="mt-2 text-xs text-surface-400">{{ b.cardCount }}枚のカード</p>
      </div>
      <div v-if="boards.length === 0" class="col-span-full py-12 text-center">
        <i class="pi pi-th-large mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">コルクボードがありません</p>
      </div>
    </div>
  </div>
</template>
