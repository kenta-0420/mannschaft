<script setup lang="ts">
import { matchGateKey } from '~/constants/featureGates'

definePageMeta({ middleware: 'auth' })

const onboardingApi = useOnboardingApi()
const { captureQuiet } = useErrorReport()
const { t } = useI18n()
const featureFlagStore = useFeatureFlagStore()

const onboardingActiveCount = ref(0)

interface MyPageCard {
  label: string
  description: string
  icon: string
  to: string
  badgeRef?: Ref<number>
}

/**
 * ハブに並べるカードの定義。
 *
 * ラベル・説明は i18n 必須（CLAUDE.md「UIに表示する文字列は直書き禁止」）。
 * 遷移先ページが独自のタイトル/サブタイトル文言を持つ場合はそのキーを再利用し、
 * 持たない場合のみ `myPage.cards.*` に専用キーを置く（文言の二重管理を避けるため）。
 *
 * `computed` にしているのはロケール切替へ追従させるため（setup 時に t() を1回だけ
 * 呼ぶ旧実装では、言語を切り替えてもラベルが元の言語のまま残る）。
 */
const cards = computed<MyPageCard[]>(() => [
  {
    label: t('myPage.cards.onboarding.label'),
    description: t('myPage.cards.onboarding.description'),
    icon: 'pi pi-check-circle',
    to: '/my/onboarding',
    badgeRef: onboardingActiveCount,
  },
  {
    label: t('myPage.cards.shift.label'),
    description: t('myPage.cards.shift.description'),
    icon: 'pi pi-calendar',
    to: '/my/shift',
  },
  {
    label: t('myPage.cards.reservations.label'),
    description: t('myPage.cards.reservations.description'),
    icon: 'pi pi-bookmark',
    to: '/my/reservations',
  },
  {
    label: t('myPage.cards.charts.label'),
    description: t('myPage.cards.charts.description'),
    icon: 'pi pi-file',
    to: '/my/charts',
  },
  {
    label: t('myPage.cards.performance.label'),
    description: t('myPage.cards.performance.description'),
    icon: 'pi pi-chart-line',
    to: '/my/performance',
  },
  {
    // F08.10 個人分析の発見可能性（§G.9）。i18n 済みラベルで「試合分析」への入口を追加。
    label: t('match.analytics.my_title'),
    description: t('match.analytics.my_subtitle'),
    icon: 'pi pi-chart-bar',
    to: '/me/match-analytics',
  },
  {
    label: t('myPage.cards.projects.label'),
    description: t('myPage.cards.projects.description'),
    icon: 'pi pi-briefcase',
    to: '/my/projects',
  },
  {
    label: t('myPage.cards.serviceRecords.label'),
    description: t('myPage.cards.serviceRecords.description'),
    icon: 'pi pi-history',
    to: '/my/service-records',
  },
  {
    label: t('myPage.cards.noShows.label'),
    description: t('myPage.cards.noShows.description'),
    icon: 'pi pi-times-circle',
    to: '/my/no-shows',
  },
  {
    label: t('myPage.cards.resume.label'),
    description: t('myPage.cards.resume.description'),
    icon: 'pi pi-file-pdf',
    to: '/my/resume',
  },
  {
    // F22.1 謝礼の受け取り・返金管理（受取側 ADMIN/本人）。
    label: t('market.payment.received.pageTitle'),
    description: t('market.payment.received.pageSubtitle'),
    icon: 'pi pi-wallet',
    to: '/me/recruitment-payments',
  },
  {
    // F03.11.1 キャンセル料の免除（受取先側の精算管理者・受取先本人・SYSTEM_ADMIN）。
    // 受取側の金銭を扱う画面であるため、謝礼の受け取り（/me/recruitment-payments）の隣に置く。
    label: t('recruitment.cancellationFeeWaive.pageTitle'),
    description: t('recruitment.cancellationFeeWaive.pageDescription'),
    icon: 'pi pi-ban',
    to: '/me/recruitment-cancellation-fees',
  },
  {
    // CMP-260909-1141 Phase 1: 領収書一覧（自分向け）。実装済みだが導線が1本も無かった。
    label: t('payment.receipt.title'),
    description: t('myPage.cards.receipts.description'),
    icon: 'pi pi-receipt',
    to: '/me/payments/receipts',
  },
  {
    // CMP-260909-1141 Phase 1: 後見まとめ払い（管理する子どもの未払い会費の一括決済）。
    label: t('payment.guardianBulkPayment.title'),
    description: t('payment.guardianBulkPayment.subtitle'),
    icon: 'pi pi-credit-card',
    to: '/me/guardianship/bulk-payment',
  },
  {
    // CMP-260909-1141 Phase 1: 大会参加費の Connect 決済。
    label: t('tournamentFee.pageTitle'),
    description: t('myPage.cards.tournamentFees.description'),
    icon: 'pi pi-trophy',
    to: '/me/tournament-fees',
  },
])

/**
 * 機能フラグで閉じられているカードを落とす。
 *
 * ## なぜ必要か
 * ここに並ぶカードのうち複数は `GATE_ROUTE_MAP` のガード対象配下にある。
 * フラグを閉じた（β公開前の運用）瞬間、カードは見えるのにクリックすると
 * `middleware/feature-gate.global.ts` に弾かれて `/dashboard` へ戻される、という壊れ方をする。
 * ナビ層（ここ）と route 層（middleware）で同じ判定を使い、見えるものは必ず踏める状態を保つ。
 *
 * ## 対応表を複製しない
 * ルート → gate_key の対応は `GATE_ROUTE_MAP` が正本であり、
 * 解決は既存の純関数 `matchGateKey()`（middleware の `decideGate` が使うのと同一）に委ねる。
 * ここに gate_key を手書きすると片方だけ直される事故が起きる。
 *
 * ## 未取得時は隠す（fail-closed）
 * `isEnabled()` は未取得のキーに false を返す。公開フラグは
 * `plugins/feature-flags.client.ts` が起動時に取得するため通常は取得済みだが、
 * 取得前の一瞬は「ガード対象カードが出ない」側に倒れる。route 層と同じく
 * fail-open（踏めないカードを見せる）にはしない。
 * ガード対象外のカード（`matchGateKey()` が null）はフラグ状態に一切依存せず常に出る。
 */
const visibleCards = computed<MyPageCard[]>(() =>
  cards.value.filter((card) => {
    const gateKey = matchGateKey(card.to)
    return gateKey === null || featureFlagStore.isEnabled(gateKey)
  }),
)

onMounted(async () => {
  try {
    const progresses = await onboardingApi.listMyProgresses()
    onboardingActiveCount.value = progresses.filter((p) => p.status === 'IN_PROGRESS').length
  } catch (error) {
    captureQuiet(error, { context: 'MyPageHub: オンボーディング件数取得' })
  }
})
</script>

<template>
  <div class="mx-auto max-w-5xl">
    <PageHeader :title="t('myPage.pageTitle')" />

    <div class="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-4">
      <NuxtLink
        v-for="card in visibleCards"
        :key="card.to"
        :to="card.to"
        class="relative flex flex-col items-center gap-2 rounded-xl border border-surface-200 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-700 dark:bg-surface-900"
      >
        <i :class="card.icon" class="text-3xl text-primary" />
        <p class="text-center text-sm font-semibold text-surface-800 dark:text-surface-100">
          {{ card.label }}
        </p>
        <p class="line-clamp-2 text-center text-xs text-surface-500">
          {{ card.description }}
        </p>
        <span
          v-if="card.badgeRef && card.badgeRef.value > 0"
          class="absolute -top-1 -right-1 flex h-5 min-w-5 items-center justify-center rounded-full bg-red-500 px-1.5 text-[10px] font-bold text-white"
        >
          {{ card.badgeRef.value }}
        </span>
      </NuxtLink>
    </div>
  </div>
</template>
