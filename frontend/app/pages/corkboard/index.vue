<script setup lang="ts">
/**
 * F09.8.1 Phase 5 + Phase A2: コルクボード一覧。
 *
 * 既存 corkboard index 画面はボード一覧のみで、個別カードは描画されない。
 * Phase 5 ではピン止め一覧（マイコルクボード）への動線とピン可能マークを追加。
 * Phase A2 ではバックエンド `CorkboardResponse` の実フィールド名（`name` /
 * `backgroundStyle`）に追従し、ボードカードクリックで scope-agnostic GET API
 * `/api/v1/corkboards/{boardId}` を叩く詳細ページ `/corkboard/{id}` へ遷移する。
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
      <NuxtLink
        v-for="b in boards"
        :key="b.id"
        :to="`/corkboard/${b.id}`"
        class="block rounded-xl border border-surface-300 bg-surface-0 p-4 transition hover:border-primary-400 hover:shadow-sm"
      >
        <div class="flex items-start justify-between gap-2">
          <h3 class="text-sm font-semibold">{{ b.name }}</h3>
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
        <p v-if="b.backgroundStyle" class="mt-2 text-xs text-surface-400">
          {{ b.backgroundStyle }}
        </p>
      </NuxtLink>
      <div v-if="boards.length === 0" class="col-span-full py-12 text-center">
        <i class="pi pi-th-large mb-3 text-4xl text-surface-300" />
        <p class="text-surface-400">コルクボードがありません</p>
      </div>
    </div>
  </div>
</template>
