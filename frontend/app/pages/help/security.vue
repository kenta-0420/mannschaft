<script setup lang="ts">
/**
 * セキュリティ画面（/settings/security）の使い方説明ページ。
 *
 * 二要素認証・アクティブセッション・セキュリティキーの3機能と、
 * 不審なログインを見つけたときの対処手順を SectionCard で分割して案内する。
 */
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})

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

useSeoMeta({
  title: () => `${t('security_help.title')} | Mannschaft`,
  description: () => t('security_help.description'),
  ogTitle: () => t('security_help.title'),
  ogDescription: () => t('security_help.description'),
  ogType: 'website',
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('security_help.title')" back-to="/settings/security" />

    <div class="space-y-6">
      <p class="text-sm text-surface-600 dark:text-surface-300">
        {{ t('security_help.description') }}
      </p>

      <!-- 二要素認証（2FA） -->
      <SectionCard :title="t('security_help.twofa.title')">
        <div class="space-y-4 text-sm text-surface-700 dark:text-surface-200">
          <p>{{ t('security_help.twofa.intro') }}</p>

          <div>
            <h3 class="mb-2 font-semibold text-surface-900 dark:text-surface-0">
              {{ t('security_help.twofa.setup_title') }}
            </h3>
            <ol class="list-decimal space-y-1 pl-5">
              <li v-for="(step, i) in twofaSetupSteps" :key="i">{{ step }}</li>
            </ol>
          </div>

          <div>
            <h3 class="mb-2 font-semibold text-surface-900 dark:text-surface-0">
              {{ t('security_help.twofa.backup_title') }}
            </h3>
            <p>{{ t('security_help.twofa.backup_desc') }}</p>
          </div>
        </div>
      </SectionCard>

      <!-- アクティブセッション -->
      <SectionCard :title="t('security_help.sessions.title')">
        <div class="space-y-3 text-sm text-surface-700 dark:text-surface-200">
          <p>{{ t('security_help.sessions.intro') }}</p>
          <p>{{ t('security_help.sessions.row_desc') }}</p>
          <ul class="list-disc space-y-1 pl-5">
            <li>{{ t('security_help.sessions.current_tag_desc') }}</li>
            <li>{{ t('security_help.sessions.revoke_desc') }}</li>
            <li>{{ t('security_help.sessions.revoke_all_desc') }}</li>
          </ul>
          <p
            class="rounded-lg bg-surface-100 px-3 py-2 text-surface-600 dark:bg-surface-700 dark:text-surface-300"
          >
            <i class="pi pi-info-circle mr-1" aria-hidden="true" />{{ t('security_help.sessions.current_note') }}
          </p>
        </div>
      </SectionCard>

      <!-- セキュリティキー（WebAuthn） -->
      <SectionCard :title="t('security_help.webauthn.title')">
        <div class="space-y-3 text-sm text-surface-700 dark:text-surface-200">
          <p>{{ t('security_help.webauthn.intro') }}</p>
          <p>{{ t('security_help.webauthn.manage_desc') }}</p>
        </div>
      </SectionCard>

      <!-- 身に覚えのないログインを見つけたら -->
      <SectionCard :title="t('security_help.suspicious.title')">
        <div class="space-y-3 text-sm text-surface-700 dark:text-surface-200">
          <p>{{ t('security_help.suspicious.intro') }}</p>
          <ol class="list-decimal space-y-1 pl-5">
            <li v-for="(step, i) in suspiciousSteps" :key="i">{{ step }}</li>
          </ol>
        </div>
      </SectionCard>
    </div>
  </div>
</template>
