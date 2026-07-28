<script setup lang="ts">
/**
 * チーム予約パネルの使い方カード本体（タブ別ガイド）。
 *
 * activeTab（0=予約する/1=予約一覧/2=予約対象の管理/3=緊急休業）に応じて、開いていたタブの
 * 実務手順だけを表示する。以前は isAdmin だけで「管理者向け全部/利用者向け全部」を出し分けて
 * いたが、マスター評「作成者の私でさえ使い方がわからない」を受け、タブごとに実際の操作導線
 * （マトリックス予約・グループ予約・週間テンプレート等）に沿った手順へ全面書き直しした。
 *
 * 予約一覧タブ（activeTab=1）のみ isAdminOrDeputy で内容を出し分ける
 * （ReservationList 自体が isAdminOrDeputy で team/mine を切り替えるため、それに合わせる）。
 * 予約対象の管理タブ（activeTab=2）・緊急休業タブ（activeTab=3）は Panel 側で
 * 既に v-if="isAdmin" / v-if="isAdminOrDeputy" によりタブそのものが管理者以外に見えないため、
 * ガイド側での追加の出し分けは不要。
 *
 * 連番オブジェクト（steps）は tm で取得し配列化して描画する（既存 resolveList 方式踏襲）。
 */
const props = defineProps<{
  isAdmin: boolean
  isAdminOrDeputy: boolean
  activeTab: number
  /**
   * 呼称の動的差し込み（F03.4.5 §5.2）。呼び出し元（TeamReservationGuideModal）が
   * useResourceName で解決した値をそのまま渡す（本コンポーネント自身は API を叩かない）。
   * 未指定時は「予約対象」相当のフォールバック文言を使う。
   */
  resourceName?: string
}>()

const { t, tm } = useI18n()

type StepRecord = Record<string, string>

/** 呼称キーを含まない文言では無視される（i18n は未使用の補間パラメータを許容する）。 */
const guideParams = computed(() => ({ resourceName: props.resourceName ?? t('reservation.resource_name.DEFAULT') }))

function resolveList(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map(k => t(`${key}.${k}`, guideParams.value))
}

interface GuideCard {
  key: string
  icon: string
  colorClass: string
  titleKey: string
  stepsKey: string
}

const COLOR = {
  blue: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  green: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400',
  amber: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400',
  purple: 'bg-purple-100 text-purple-600 dark:bg-purple-900/30 dark:text-purple-400',
  red: 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400',
} as const

/** タブ0「予約する」: マトリックスの見方→メニュー絞り込み→予約の流れ→満席・競合時の案内。 */
const bookCards: GuideCard[] = [
  { key: 'matrix', icon: 'pi pi-table', colorClass: COLOR.blue, titleKey: 'reservation.team_guide.book.matrix_title', stepsKey: 'reservation.team_guide.book.matrix_steps' },
  { key: 'filter', icon: 'pi pi-filter', colorClass: COLOR.green, titleKey: 'reservation.team_guide.book.filter_title', stepsKey: 'reservation.team_guide.book.filter_steps' },
  { key: 'flow', icon: 'pi pi-calendar-plus', colorClass: COLOR.amber, titleKey: 'reservation.team_guide.book.flow_title', stepsKey: 'reservation.team_guide.book.flow_steps' },
  { key: 'conflict', icon: 'pi pi-exclamation-triangle', colorClass: COLOR.red, titleKey: 'reservation.team_guide.book.conflict_title', stepsKey: 'reservation.team_guide.book.conflict_steps' },
]

/** タブ1「予約一覧」管理者向け: チーム全体の予約を見る→承認/却下/キャンセル。 */
const listAdminCards: GuideCard[] = [
  { key: 'overview', icon: 'pi pi-list', colorClass: COLOR.blue, titleKey: 'reservation.team_guide.list.admin_overview_title', stepsKey: 'reservation.team_guide.list.admin_overview_steps' },
  { key: 'manage', icon: 'pi pi-check-circle', colorClass: COLOR.green, titleKey: 'reservation.team_guide.list.admin_manage_title', stepsKey: 'reservation.team_guide.list.admin_manage_steps' },
]

/** タブ1「予約一覧」利用者向け: 自分の予約の確認→キャンセル。 */
const listMemberCards: GuideCard[] = [
  { key: 'mine', icon: 'pi pi-user', colorClass: COLOR.blue, titleKey: 'reservation.team_guide.list.member_mine_title', stepsKey: 'reservation.team_guide.list.member_mine_steps' },
  { key: 'cancel', icon: 'pi pi-times-circle', colorClass: COLOR.amber, titleKey: 'reservation.team_guide.list.member_cancel_title', stepsKey: 'reservation.team_guide.list.member_cancel_steps' },
]

/**
 * タブ2「予約対象の管理」: 初期セットアップ①営業時間→②予約対象→③メニュー→④週間スケジュール
 * →日常運用（基本なにもしなくてよい）→例外時の対応（F03.4.5 §3.2 管理タブ再編に合わせた並び）。
 * setup0（営業時間）は F03.4.5 で新設した最初の手順（手助け・BusinessHoursManager 追記）。
 */
const lineManageCards: GuideCard[] = [
  { key: 'setup0', icon: 'pi pi-clock', colorClass: COLOR.blue, titleKey: 'reservation.team_guide.line_manage.setup0_title', stepsKey: 'reservation.team_guide.line_manage.setup0_steps' },
  { key: 'setup1', icon: 'pi pi-list', colorClass: COLOR.green, titleKey: 'reservation.team_guide.line_manage.setup1_title', stepsKey: 'reservation.team_guide.line_manage.setup1_steps' },
  { key: 'setup2', icon: 'pi pi-book', colorClass: COLOR.amber, titleKey: 'reservation.team_guide.line_manage.setup2_title', stepsKey: 'reservation.team_guide.line_manage.setup2_steps' },
  { key: 'setup3', icon: 'pi pi-calendar-plus', colorClass: COLOR.purple, titleKey: 'reservation.team_guide.line_manage.setup3_title', stepsKey: 'reservation.team_guide.line_manage.setup3_steps' },
  { key: 'daily', icon: 'pi pi-refresh', colorClass: COLOR.blue, titleKey: 'reservation.team_guide.line_manage.daily_title', stepsKey: 'reservation.team_guide.line_manage.daily_steps' },
  { key: 'exception', icon: 'pi pi-info-circle', colorClass: COLOR.red, titleKey: 'reservation.team_guide.line_manage.exception_title', stepsKey: 'reservation.team_guide.line_manage.exception_steps' },
]

/** タブ3「緊急休業」: 送信すると誰に何が届くか→確認状況の見方。 */
const emergencyClosureCards: GuideCard[] = [
  { key: 'send', icon: 'pi pi-send', colorClass: COLOR.red, titleKey: 'reservation.team_guide.emergency_closure.send_title', stepsKey: 'reservation.team_guide.emergency_closure.send_steps' },
  { key: 'status', icon: 'pi pi-eye', colorClass: COLOR.blue, titleKey: 'reservation.team_guide.emergency_closure.status_title', stepsKey: 'reservation.team_guide.emergency_closure.status_steps' },
]

const cards = computed<GuideCard[]>(() => {
  switch (props.activeTab) {
    case 1:
      return props.isAdminOrDeputy ? listAdminCards : listMemberCards
    case 2:
      return lineManageCards
    case 3:
      return emergencyClosureCards
    case 0:
    default:
      return bookCards
  }
})
</script>

<template>
  <div class="space-y-4">
    <SectionCard
      v-for="card in cards"
      :key="card.key"
    >
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full" :class="card.colorClass">
          <i :class="card.icon" class="text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(card.titleKey, guideParams) }}
          </h2>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li
              v-for="(step, i) in resolveList(card.stepsKey)"
              :key="i"
            >
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
