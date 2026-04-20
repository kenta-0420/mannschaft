<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const onboardingApi = useOnboardingApi()
const { captureQuiet } = useErrorReport()

const onboardingActiveCount = ref(0)

interface MyPageCard {
  label: string
  description: string
  icon: string
  to: string
  badgeRef?: Ref<number>
}

const cards: MyPageCard[] = [
  {
    label: 'オンボーディング',
    description: '参加したチーム・組織のやるべき手続き',
    icon: 'pi pi-check-circle',
    to: '/my/onboarding',
    badgeRef: onboardingActiveCount,
  },
  {
    label: 'マイシフト',
    description: '自分のシフトの確認',
    icon: 'pi pi-calendar',
    to: '/my/shifts',
  },
  {
    label: 'マイ予約',
    description: '自分の予約一覧',
    icon: 'pi pi-bookmark',
    to: '/my/reservations',
  },
  {
    label: 'マイカルテ',
    description: '活動記録・メモの確認',
    icon: 'pi pi-file',
    to: '/my/charts',
  },
  {
    label: 'マイパフォーマンス',
    description: '自分の成績・実績',
    icon: 'pi pi-chart-line',
    to: '/my/performance',
  },
  {
    label: 'マイプロジェクト',
    description: '個人プロジェクトの管理',
    icon: 'pi pi-briefcase',
    to: '/my/projects',
  },
  {
    label: 'マイサービス履歴',
    description: '受けたサービスの履歴',
    icon: 'pi pi-history',
    to: '/my/service-records',
  },
  {
    label: 'キャンセル履歴',
    description: '無断キャンセル・ペナルティ履歴',
    icon: 'pi pi-times-circle',
    to: '/my/no-shows',
  },
]

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
    <PageHeader title="マイページ" />

    <div class="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-4">
      <NuxtLink
        v-for="card in cards"
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
