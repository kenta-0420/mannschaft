<script setup lang="ts">
const { t, tm } = useI18n()

type StepRecord = Record<string, string>

// 連番ステップ（step1, step2...）を順序付き配列へ正規化（tm() の値を t() で個別解決）
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const audienceSteps = computed<string[]>(() => resolveSteps('announcement.broadcast_guide.audience.steps'))
const templateSteps = computed<string[]>(() => resolveSteps('announcement.broadcast_guide.template.steps'))
const channelSteps = computed<string[]>(() => resolveSteps('announcement.broadcast_guide.channel.steps'))
const contentSteps = computed<string[]>(() => resolveSteps('announcement.broadcast_guide.content.steps'))
const prioritySteps = computed<string[]>(() => resolveSteps('announcement.broadcast_guide.priority.steps'))
const submitSteps = computed<string[]>(() => resolveSteps('announcement.broadcast_guide.submit.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('announcement.broadcast_guide.description') }}
    </p>

    <!-- セクション1: 誰に届けるか（対象範囲） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-users text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('announcement.broadcast_guide.audience.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('announcement.broadcast_guide.audience.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in audienceSteps" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション2: テンプレートで時短 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-bookmark text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('announcement.broadcast_guide.template.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('announcement.broadcast_guide.template.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in templateSteps" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション3: 届け方を選ぶ（チャネル） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-teal-100 text-teal-600 dark:bg-teal-900/30 dark:text-teal-400">
          <i class="pi pi-share-alt text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('announcement.broadcast_guide.channel.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('announcement.broadcast_guide.channel.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in channelSteps" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション4: 内容を書く -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-pencil text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('announcement.broadcast_guide.content.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('announcement.broadcast_guide.content.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in contentSteps" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション5: 優先度と表示期限 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-flag text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('announcement.broadcast_guide.priority.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('announcement.broadcast_guide.priority.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in prioritySteps" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>

    <!-- セクション6: 送信して確認 -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-send text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('announcement.broadcast_guide.submit.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('announcement.broadcast_guide.submit.body') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in submitSteps" :key="i">
              {{ step }}
            </li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
