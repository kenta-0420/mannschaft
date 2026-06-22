<script setup lang="ts">
/**
 * F06.5 今日の振り返りビュー（§4・AC-17/19・§7 #12）。
 *
 * 今日の全コマ（TEAM/PERSONAL・時刻順）＋自由テーマ由来 item を縦並びで表示する。
 * - コマ由来 item（slotKind 非 null）と自由テーマ由来 item（slotKind=null）を描き分ける（§4.3）。
 * - 空きコマ（themeId=null）もテーマ作成導線を出す（AC-17）。
 * - 各コマの当日エントリ保存は個別（ReflectionEntryDialog で upsert）。
 */
import type { ReflectionTodayItem, ReflectionEntryResponse } from '~/types/reflection'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const notification = useNotification()
const reflectionApi = useReflectionApi()
const router = useRouter()

const loading = ref(true)
const date = ref<string>('')
const items = ref<ReflectionTodayItem[]>([])

// 使い方モーダル
const guideVisible = ref(false)

// エントリダイアログ状態
const dialogVisible = ref(false)
const dialogThemeId = ref<string>('')
const dialogEntry = ref<ReflectionEntryResponse | null>(null)

// コマ由来 item と自由テーマ由来 item を分離（§4.3）。
const slotItems = computed(() => items.value.filter(i => i.slotKind))
const freeItems = computed(() => items.value.filter(i => !i.slotKind))

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await reflectionApi.getToday()
    date.value = res.data.date ?? ''
    items.value = res.data.items ?? []
  }
  catch {
    notification.error(t('reflection.common.load_failed'))
  }
  finally {
    loading.value = false
  }
}

/** 当日エントリの作成/編集を開く。既存エントリは詳細取得してから開く（structured_content 取得）。 */
async function openEntry(item: ReflectionTodayItem) {
  if (!item.themeId) return
  dialogThemeId.value = item.themeId
  if (item.entryId && item.hasEntryToday) {
    try {
      const res = await reflectionApi.getEntry(item.entryId)
      // 当日マスク中（通常は非マスク）なら想起テストへ誘導（直接編集 409 回避）。
      if (res.data.isMasked) {
        await router.push(`/reflections/recall?entry=${item.entryId}`)
        return
      }
      dialogEntry.value = res.data
    }
    catch {
      notification.error(t('reflection.common.load_failed'))
      return
    }
  }
  else {
    dialogEntry.value = null
  }
  dialogVisible.value = true
}

/** 空きコマ／自由テーマ未設定からテーマ作成画面へ。 */
function createThemeForSlot(item: ReflectionTodayItem) {
  const params = new URLSearchParams()
  if (item.slotKind) params.set('slotKind', item.slotKind)
  if (item.slotId != null) params.set('slotId', String(item.slotId))
  if (item.subjectName) params.set('title', item.subjectName)
  router.push(`/reflections/themes?create=1&${params.toString()}`)
}

function onSaved() {
  load()
}
</script>

<template>
  <div class="mx-auto max-w-2xl px-4 py-6">
    <PageHeader
      :title="t('reflection.today.heading')"
      back-to="/dashboard"
      :back-label="t('reflection.nav.dashboard')"
      help
      @help="guideVisible = true"
    >
      <template #actions>
        <Button
          v-tooltip.bottom="t('reflection.theme.create')"
          icon="pi pi-plus"
          text
          rounded
          :aria-label="t('reflection.theme.create')"
          @click="router.push('/reflections/themes?create=1')"
        />
        <Button
          v-tooltip.bottom="t('reflection.nav.themes')"
          icon="pi pi-list"
          text
          rounded
          :aria-label="t('reflection.nav.themes')"
          @click="router.push('/reflections/themes')"
        />
        <Button
          v-tooltip.bottom="t('reflection.nav.settings')"
          icon="pi pi-cog"
          text
          rounded
          :aria-label="t('reflection.nav.settings')"
          @click="router.push('/reflections/settings')"
        />
      </template>
    </PageHeader>

    <p v-if="date" class="mb-4 text-sm text-surface-500">{{ date }}</p>

    <div v-if="loading" class="space-y-3">
      <Skeleton height="72px" />
      <Skeleton height="72px" />
      <Skeleton height="72px" />
    </div>

    <SectionCard v-else-if="items.length === 0" class="text-center">
      <p class="mb-3 text-sm text-surface-500">{{ t('reflection.today.empty') }}</p>
      <Button :label="t('reflection.theme.create')" icon="pi pi-plus" @click="router.push('/reflections/themes?create=1')" />
    </SectionCard>

    <div v-else class="space-y-6">
      <!-- 時間割コマ由来 -->
      <SectionCard v-if="slotItems.length > 0">
        <template #header>
          <h2 class="text-sm font-semibold text-surface-600 dark:text-surface-300">
            {{ t('reflection.today.timetable_slots') }}
          </h2>
        </template>
        <div class="space-y-2">
          <ReflectionTodayItemCard
            v-for="(item, i) in slotItems"
            :key="`slot-${item.slotKind}-${item.slotId}-${i}`"
            :item="item"
            @open="openEntry"
            @create-theme="createThemeForSlot"
          />
        </div>
      </SectionCard>

      <!-- 自由テーマ由来（社会人・日記の主導線） -->
      <SectionCard v-if="freeItems.length > 0">
        <template #header>
          <h2 class="text-sm font-semibold text-surface-600 dark:text-surface-300">
            {{ t('reflection.today.free_themes') }}
          </h2>
        </template>
        <div class="space-y-2">
          <ReflectionTodayItemCard
            v-for="(item, i) in freeItems"
            :key="`free-${item.themeId}-${i}`"
            :item="item"
            @open="openEntry"
            @create-theme="createThemeForSlot"
          />
        </div>
      </SectionCard>
    </div>

    <ReflectionEntryDialog
      v-model:visible="dialogVisible"
      :theme-id="dialogThemeId"
      :target-date="date"
      :entry="dialogEntry"
      @saved="onSaved"
    />
    <ReflectionGuideModal v-model:visible="guideVisible" />
  </div>
</template>
