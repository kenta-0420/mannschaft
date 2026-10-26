<script setup lang="ts">
/**
 * ニュースレター設定画面の使い方を案内するカード本体。
 * VillageRecruitCategoryGuideContent.vue を金型に、週次/月次の独立オン・オフ、配信タイミング、
 * 権限、個人の受信設定、そして「配信機能は準備中」の注記を説明する。
 */
const { t, tm } = useI18n()

type StepRecord = Record<string, string>
/** 連番ステップ（step1, step2...）を順序付き配列へ正規化（tm() の値を t() で個別解決）。 */
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map(k => t(`${key}.${k}`))
}

interface GuideCard {
  key: string
  icon: string
  iconClass: string
}

const cards: GuideCard[] = [
  { key: 'toggles', icon: 'pi pi-bell', iconClass: 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400' },
  { key: 'timing', icon: 'pi pi-calendar', iconClass: 'bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400' },
  { key: 'permission', icon: 'pi pi-shield', iconClass: 'bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400' },
  { key: 'personal', icon: 'pi pi-user', iconClass: 'bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400' },
]
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('village.newsletterGuide.description') }}
    </p>

    <SectionCard v-for="card in cards" :key="card.key">
      <div class="flex items-start gap-4">
        <div
          class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full"
          :class="card.iconClass"
        >
          <i :class="`${card.icon} text-xl`" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t(`village.newsletterGuide.${card.key}.title`) }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t(`village.newsletterGuide.${card.key}.body`) }}
          </p>
          <ol
            v-if="resolveSteps(`village.newsletterGuide.${card.key}.steps`).length"
            class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300"
          >
            <li v-for="(step, i) in resolveSteps(`village.newsletterGuide.${card.key}.steps`)" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- 配信機能は準備中である旨（マスター御裁可・対処療法で隠さない） -->
    <Message severity="warn" :closable="false">
      {{ t('village.newsletterGuide.comingSoon.body') }}
    </Message>
  </div>
</template>
