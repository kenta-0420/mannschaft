<script setup lang="ts">
/**
 * F17.1 村機能 — VillageHeader 共通コンポーネント
 *
 * 村詳細ページ（掲示板 / タイムライン / ロビー / メンバー）で共通利用する
 * 上部ヘッダー UI。
 *
 * - カバー画像 + 村アイコン + 村名 + 公式バッジ
 * - メタ情報（種別 / 参加方式 / 公開範囲 / カテゴリ / 村人数）
 * - アクション群（参加・退出・ピン・通報・編集）— 権限と状態で出し分け
 * - タブナビ（PrimeVue Tabs / Tab）
 *
 * 設計書: docs/features/F17.1_village_community.md §4.1 / §4.3 / §4.8
 *
 * 注意:
 *   - 型は FE1 完成版 (frontend/app/types/village.ts) を必ず使う。
 *   - 通報ダイアログ表示は FE5 担当のため、本コンポは `report-click` を emit するのみ。
 *   - 画像は BE が署名付き表示 URL（iconUrl/coverUrl/monshoUrl）に解決して返す（#2355）。
 *     FE は公開ベース URL の前置をせず、そのまま <img src> に渡す。
 */
import type { VillageResponse } from '~/types/village'

const props = defineProps<{
  village: VillageResponse
  activeTab:
    | 'bulletin'
    | 'timeline'
    | 'lobby'
    | 'members'
    | 'calendar'
    | 'festival'
    | 'matchRecruit'
    | 'meetup'
    | 'chronicle'
}>()

const emit = defineEmits<{
  /** 使い方モーダル表示要求 */
  help: []
  /** FREE 村における即時参加要求 */
  join: []
  /** APPROVAL 村における参加申請フロー遷移要求 */
  requestJoin: []
  /** 退村要求 */
  leave: []
  /** ピン留め要求 */
  pin: []
  /** ピン解除要求 */
  unpin: []
  /** 通報ダイアログ表示要求（FE5 でハンドリング） */
  'report-click': []
  /** 編集画面への遷移要求 */
  edit: []
}>()

const { t } = useI18n()

// =============================================================================
// 表示用 URL — BE が署名付きで解決済み（#2355）。前置なしでそのまま <img src> に渡す。
// =============================================================================
const iconUrl = computed<string | null>(() => props.village.iconUrl)
const coverUrl = computed<string | null>(() => props.village.coverUrl)
const monshoUrl = computed<string | null>(() => props.village.monshoUrl)

// Phase 2: 村紋プレビュー表示制御
const monshoPreviewVisible = ref(false)
function toggleMonshoPreview() {
  if (monshoUrl.value) {
    monshoPreviewVisible.value = !monshoPreviewVisible.value
  }
}

// =============================================================================
// 表示用 派生値
// =============================================================================
const typeLabel = computed(() => t(`village.type.${props.village.type}`))
const joinPolicyLabel = computed(() => t(`village.joinPolicy.${props.village.joinPolicy}`))
const visibilityLabel = computed(() => t(`village.visibility.${props.village.visibility}`))

/** イニシャル（アイコン未設定時のフォールバック） */
const initials = computed(() => {
  const name = props.village.name ?? ''
  if (!name) return '?'
  // 1〜2 文字を抽出（全角 1 文字でも OK）
  return [...name].slice(0, 2).join('').toUpperCase()
})

// =============================================================================
// 出し分け条件
// =============================================================================
const isMember = computed(() => props.village.isMember)
const isPinned = computed(() => props.village.isPinned)
const isOfficial = computed(() => props.village.isOfficial)
const canEdit = computed(() => props.village.myRole === 'HEADMAN')
const isFree = computed(() => props.village.joinPolicy === 'FREE')
/**
 * F17.1 P3（村長コンソール）— 「村の運営」ボタンの表示条件。
 * HEADMAN or ELDER（`useVillageContext().perms.isAdmin` と同じ意味論）。
 * VillageHeader は `village` プロパティしか受け取らないため、ここで直接 myRole から導出する
 * （canEdit と同じ作法）。
 */
const isAdmin = computed(() => props.village.myRole === 'HEADMAN' || props.village.myRole === 'ELDER')

// =============================================================================
// タブナビ — PrimeVue Tabs (v-model:value)
// =============================================================================
interface TabDef {
  key:
    | 'bulletin'
    | 'timeline'
    | 'lobby'
    | 'members'
    | 'calendar'
    | 'festival'
    | 'matchRecruit'
    | 'meetup'
    | 'chronicle'
  to: string
  icon: string
  i18nKey: string
}

const tabs = computed<TabDef[]>(() => [
  {
    key: 'bulletin',
    to: `/villages/${props.village.id}/bulletin`,
    icon: 'pi pi-megaphone',
    i18nKey: 'village.tab.bulletin',
  },
  {
    key: 'timeline',
    to: `/villages/${props.village.id}/timeline`,
    icon: 'pi pi-clock',
    i18nKey: 'village.tab.timeline',
  },
  {
    key: 'lobby',
    to: `/villages/${props.village.id}/lobby`,
    icon: 'pi pi-comments',
    i18nKey: 'village.tab.lobby',
  },
  {
    key: 'members',
    to: `/villages/${props.village.id}/members`,
    icon: 'pi pi-users',
    i18nKey: 'village.tab.members',
  },
  // Phase 2: 歳時記カレンダー
  {
    key: 'calendar',
    to: `/villages/${props.village.id}/calendar`,
    icon: 'pi pi-calendar',
    i18nKey: 'village.tab.calendar',
  },
  // Phase 2: お祭り
  {
    key: 'festival',
    to: `/villages/${props.village.id}/festivals`,
    icon: 'pi pi-star',
    i18nKey: 'village.tab.festival',
  },
  // Phase 2: 練習試合・募集
  {
    key: 'matchRecruit',
    to: `/villages/${props.village.id}/match-recruits`,
    icon: 'pi pi-flag',
    i18nKey: 'village.tab.matchRecruit',
  },
  // Phase 3: 寄合
  {
    key: 'meetup',
    to: `/villages/${props.village.id}/meetups`,
    icon: 'pi pi-calendar-plus',
    i18nKey: 'village.tab.meetup',
  },
  // Phase 3: 村史
  {
    key: 'chronicle',
    to: `/villages/${props.village.id}/chronicles`,
    icon: 'pi pi-book',
    i18nKey: 'village.tab.chronicle',
  },
])

// =============================================================================
// アクションハンドラ
// =============================================================================
function onJoinClick() {
  if (isFree.value) {
    emit('join')
  }
  else {
    emit('requestJoin')
  }
}

function onPinToggle() {
  if (isPinned.value) {
    emit('unpin')
  }
  else {
    emit('pin')
  }
}
</script>

<template>
  <div class="village-header">
    <!-- ============================================================== -->
    <!-- 1. カバー画像                                                    -->
    <!-- ============================================================== -->
    <div class="relative w-full overflow-hidden h-40 sm:h-56">
      <img
        v-if="coverUrl"
        :src="coverUrl"
        :alt="village.name"
        class="w-full h-full object-cover object-center"
      >
      <div
        v-else
        class="w-full h-full bg-gradient-to-br from-primary-300 via-primary-500 to-primary-700"
      />
    </div>

    <!-- ============================================================== -->
    <!-- 2. アイコン + 村名 + バッジ + アクション群                         -->
    <!-- ============================================================== -->
    <div class="relative px-4 sm:px-6 pb-3 pt-2 bg-surface-0 dark:bg-surface-900">
      <!-- アイコン（カバー下端と重なる位置） -->
      <div
        class="absolute -top-10 sm:-top-12 left-4 sm:left-6 w-20 h-20 sm:w-24 sm:h-24 rounded-full overflow-hidden border-4 border-white dark:border-surface-900 shadow-lg bg-surface-200"
      >
        <img
          v-if="iconUrl"
          :src="iconUrl"
          :alt="village.name"
          class="w-full h-full object-cover"
        >
        <div
          v-else
          class="w-full h-full flex items-center justify-center text-white font-bold text-2xl bg-primary-500"
        >
          {{ initials }}
        </div>
      </div>

      <!-- 名前行（アイコンと干渉しないよう左マージン） -->
      <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3 pl-24 sm:pl-28">
        <!-- 左: 村名 + メタ -->
        <div class="flex flex-col gap-1 min-w-0">
          <div class="flex items-center gap-2 flex-wrap">
            <h1 class="text-xl sm:text-2xl font-bold truncate">
              {{ village.name }}
            </h1>
            <!-- Phase 2: 村紋アイコン -->
            <button
              v-if="monshoUrl"
              type="button"
              class="village-header__monsho-button"
              :aria-label="t('village.monsho.title')"
              :title="t('village.monsho.title')"
              @click="toggleMonshoPreview"
            >
              <img
                :src="monshoUrl"
                :alt="t('village.monsho.title')"
                class="w-7 h-7 sm:w-8 sm:h-8 rounded-full object-cover border border-surface-300 shadow-sm bg-white"
              >
            </button>
            <!-- 公式バッジ -->
            <Badge
              v-if="isOfficial"
              :value="typeLabel"
              severity="warn"
              class="village-header__official-badge"
            >
              <i class="pi pi-crown mr-1" />
              {{ typeLabel }}
            </Badge>
          </div>

          <!-- メタ情報 -->
          <div class="flex items-center gap-2 flex-wrap text-xs sm:text-sm text-surface-600 dark:text-surface-300">
            <span v-if="!isOfficial">
              <i class="pi pi-tag mr-1" />{{ typeLabel }}
            </span>
            <span>
              <i class="pi pi-sign-in mr-1" />{{ joinPolicyLabel }}
            </span>
            <span>
              <i class="pi pi-eye mr-1" />{{ visibilityLabel }}
            </span>
            <span v-if="village.category">
              <i class="pi pi-bookmark mr-1" />{{ village.category }}
            </span>
            <span>
              <i class="pi pi-users mr-1" />{{ t('village.field.memberCount') }}: {{ village.memberCount }}
            </span>
          </div>
        </div>

        <!-- 右: アクション群 -->
        <div class="flex items-center gap-2 flex-wrap">
          <!-- 使い方モーダル（全員に常時表示） -->
          <Button
            :label="t('button.help')"
            :aria-label="t('button.help')"
            icon="pi pi-question-circle"
            severity="secondary"
            size="small"
            text
            data-testid="village-guide-help"
            @click="emit('help')"
          />
          <!-- 参加ボタン（未加入のみ） -->
          <Button
            v-if="!isMember"
            :label="t('village.action.join')"
            icon="pi pi-sign-in"
            severity="primary"
            size="small"
            @click="onJoinClick"
          />

          <!-- ピン留めトグル（メンバーのみ） -->
          <Button
            v-if="isMember"
            :label="isPinned ? t('village.action.unpin') : t('village.action.pin')"
            :icon="isPinned ? 'pi pi-star-fill' : 'pi pi-star'"
            :severity="isPinned ? 'warn' : 'secondary'"
            size="small"
            outlined
            @click="onPinToggle"
          />

          <!-- 通報ボタン（メンバーのみ・Dialog 表示は FE5 担当） -->
          <Button
            v-if="isMember"
            :aria-label="t('village.action.report')"
            icon="pi pi-flag"
            severity="secondary"
            size="small"
            text
            @click="emit('report-click')"
          />

          <!-- 編集ボタン（村長のみ） -->
          <Button
            v-if="canEdit"
            :label="t('village.action.edit')"
            icon="pi pi-pencil"
            severity="secondary"
            size="small"
            outlined
            @click="emit('edit')"
          />

          <!--
            村の運営ボタン（HEADMAN or ELDER。F17.1 P3 村長コンソール §3.3）。
            単なる画面遷移のため親への emit は不要（NuxtLink で直接遷移）。
          -->
          <NuxtLink
            v-if="isAdmin"
            :to="`/villages/${village.id}/admin`"
          >
            <Button
              :label="t('village.admin.title')"
              :aria-label="t('village.admin.title')"
              icon="pi pi-cog"
              severity="secondary"
              size="small"
              outlined
              data-testid="village-admin-console-link"
            />
          </NuxtLink>

          <!-- 退村ボタン（メンバーのみ） -->
          <Button
            v-if="isMember"
            :label="t('village.action.leave')"
            icon="pi pi-sign-out"
            severity="danger"
            size="small"
            text
            @click="emit('leave')"
          />
        </div>
      </div>
    </div>

    <!-- ============================================================== -->
    <!-- 3. タブナビ                                                      -->
    <!-- ============================================================== -->
    <div class="border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <Tabs :value="activeTab">
        <TabList>
          <Tab
            v-for="tab in tabs"
            :key="tab.key"
            :value="tab.key"
            as="div"
            class="village-header__tab"
          >
            <NuxtLink
              :to="tab.to"
              class="flex items-center gap-2 no-underline text-inherit"
            >
              <i :class="tab.icon" />
              <span>{{ t(tab.i18nKey) }}</span>
            </NuxtLink>
          </Tab>
        </TabList>
      </Tabs>
    </div>

    <!-- ============================================================== -->
    <!-- Phase 2: 村紋プレビュー Dialog                                   -->
    <!-- ============================================================== -->
    <Dialog
      v-if="monshoUrl"
      v-model:visible="monshoPreviewVisible"
      modal
      :draggable="false"
      :header="t('village.monsho.title')"
      :style="{ width: '24rem' }"
      :breakpoints="{ '640px': '90vw' }"
    >
      <div class="flex items-center justify-center p-4">
        <img
          :src="monshoUrl"
          :alt="t('village.monsho.title')"
          class="max-w-full max-h-[60vh] object-contain"
        >
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.village-header__official-badge {
  display: inline-flex;
  align-items: center;
}
.village-header__tab {
  cursor: pointer;
}
</style>
