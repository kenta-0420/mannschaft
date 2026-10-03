<script setup lang="ts">
/**
 * セキュリティ設定の使い方ガイドの本体コンテンツ。
 *
 * プロジェクト使い方ガイド（ProjectGuideContent）と同じ
 * 「色付き丸アイコン＋カード」方式で、二要素認証・アクティブセッション・
 * セキュリティキーの3機能と、不審なログインを見つけたときの対処手順を案内する。
 * i18n: security_help.*
 */
const { t, tm } = useI18n()

type StepRecord = Record<string, string>

// i18n の連番ステップ（step1, step2...）を順序付き配列として取り出す。
// tm() は locale message を返すため、値は t() で個別解決して string 配列に正規化する。
function resolveSteps(key: string): string[] {
  const raw = tm(key) as StepRecord | null
  if (!raw || typeof raw !== 'object') return []
  return Object.keys(raw).map((k) => t(`${key}.${k}`))
}

const twofaSetupSteps = computed<string[]>(() => resolveSteps('security_help.twofa.setup_steps'))
const suspiciousSteps = computed<string[]>(() => resolveSteps('security_help.suspicious.steps'))
</script>

<template>
  <div class="space-y-4">
    <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
      {{ t('security_help.description') }}
    </p>

    <!-- 二要素認証（2FA） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400">
          <i class="pi pi-shield text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('security_help.twofa.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('security_help.twofa.intro') }}
          </p>

          <div class="mb-3 rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="mb-2 text-xs font-semibold text-surface-700 dark:text-surface-200">
              {{ t('security_help.twofa.setup_title') }}
            </p>
            <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
              <li v-for="(step, i) in twofaSetupSteps" :key="i">{{ step }}</li>
            </ol>
          </div>

          <div class="rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <p class="mb-1 text-xs font-semibold text-surface-700 dark:text-surface-200">
              {{ t('security_help.twofa.backup_title') }}
            </p>
            <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('security_help.twofa.backup_desc') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- アクティブセッション -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
          <i class="pi pi-desktop text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('security_help.sessions.title') }}
          </h2>
          <p class="mb-2 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('security_help.sessions.intro') }}
          </p>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('security_help.sessions.row_desc') }}
          </p>
          <ul class="mb-3 space-y-1 text-sm text-surface-600 dark:text-surface-300">
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('security_help.sessions.current_tag_desc') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('security_help.sessions.revoke_desc') }}</span>
            </li>
            <li class="flex items-start gap-2">
              <i class="pi pi-check mt-0.5 text-green-500" aria-hidden="true" />
              <span>{{ t('security_help.sessions.revoke_all_desc') }}</span>
            </li>
          </ul>
          <div class="flex items-start gap-2 rounded-lg bg-surface-50 p-3 dark:bg-surface-800">
            <i class="pi pi-info-circle mt-0.5 text-blue-500" aria-hidden="true" />
            <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
              {{ t('security_help.sessions.current_note') }}
            </p>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- セキュリティキー（WebAuthn） -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400">
          <i class="pi pi-key text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('security_help.webauthn.title') }}
          </h2>
          <p class="mb-2 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('security_help.webauthn.intro') }}
          </p>
          <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('security_help.webauthn.manage_desc') }}
          </p>
        </div>
      </div>
    </SectionCard>

    <!-- 身に覚えのないログインを見つけたら -->
    <SectionCard>
      <div class="flex items-start gap-4">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400">
          <i class="pi pi-exclamation-triangle text-xl" aria-hidden="true" />
        </div>
        <div class="w-full">
          <h2 class="mb-2 text-lg font-semibold">
            {{ t('security_help.suspicious.title') }}
          </h2>
          <p class="mb-3 text-sm leading-relaxed text-surface-600 dark:text-surface-300">
            {{ t('security_help.suspicious.intro') }}
          </p>
          <ol class="list-decimal space-y-1 pl-5 text-sm text-surface-600 dark:text-surface-300">
            <li v-for="(step, i) in suspiciousSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </div>
    </SectionCard>
  </div>
</template>
