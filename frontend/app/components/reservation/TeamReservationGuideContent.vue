<script setup lang="ts">
/**
 * チーム予約パネルの使い方カード本体。
 * isAdmin=true: ①予約対象を作る→②予約枠を作る→③公開範囲を決める＋確認/緊急休業。
 * isAdmin=false: 予約の取り方・ステータスの見方・キャンセル方法。
 * 連番オブジェクト（steps）は tm で取得し配列化して描画する（ReservationGuideContent と同方式）。
 */
const props = defineProps<{ isAdmin: boolean }>()

const { t, tm } = useI18n()

type StepRecord = Record<string, string>

function resolveList(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

interface GuideCard {
  key: string
  icon: string
  colorClass: string
  titleKey: string
  stepsKey: string
}

/** 管理者向け: 予約セットアップの3ステップ＋運用（確認・緊急休業）。 */
const adminCards: GuideCard[] = [
  { key: 'lines', icon: 'pi pi-list', colorClass: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400', titleKey: 'reservation.team_guide.admin.lines_title', stepsKey: 'reservation.team_guide.admin.lines_steps' },
  { key: 'slots', icon: 'pi pi-calendar-plus', colorClass: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400', titleKey: 'reservation.team_guide.admin.slots_title', stepsKey: 'reservation.team_guide.admin.slots_steps' },
  { key: 'publish', icon: 'pi pi-lock', colorClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400', titleKey: 'reservation.team_guide.admin.publish_title', stepsKey: 'reservation.team_guide.admin.publish_steps' },
  { key: 'manage', icon: 'pi pi-info-circle', colorClass: 'bg-purple-100 text-purple-600 dark:bg-purple-900/30 dark:text-purple-400', titleKey: 'reservation.team_guide.admin.manage_title', stepsKey: 'reservation.team_guide.admin.manage_steps' },
]

/** 利用者向け: 予約の取り方・ステータス・キャンセル。 */
const memberCards: GuideCard[] = [
  { key: 'book', icon: 'pi pi-calendar-plus', colorClass: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400', titleKey: 'reservation.team_guide.member.book_title', stepsKey: 'reservation.team_guide.member.book_steps' },
  { key: 'status', icon: 'pi pi-tag', colorClass: 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400', titleKey: 'reservation.team_guide.member.status_title', stepsKey: 'reservation.team_guide.member.status_steps' },
  { key: 'cancel', icon: 'pi pi-times-circle', colorClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400', titleKey: 'reservation.team_guide.member.cancel_title', stepsKey: 'reservation.team_guide.member.cancel_steps' },
]

const cards = computed<GuideCard[]>(() => (props.isAdmin ? adminCards : memberCards))
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
            {{ t(card.titleKey) }}
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
