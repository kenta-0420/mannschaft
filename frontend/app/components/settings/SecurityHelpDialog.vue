<script setup lang="ts">
/**
 * セキュリティ設定の使い方を案内するモーダルダイアログ。
 *
 * 以前は別ページ（/help/security）として実装していたが、ページ遷移をやめて
 * セキュリティ設定画面上でモーダル表示する方針に変更した。
 *
 * 二要素認証・アクティブセッション・セキュリティキーの3機能と、
 * 不審なログインを見つけたときの対処手順を案内する（i18n: security_help.*）。
 */
const visible = defineModel<boolean>('visible', { default: false })

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
  <Dialog
    v-model:visible="visible"
    :header="t('security_help.title')"
    :modal="true"
    :dismissable-mask="true"
    class="w-full max-w-2xl"
    :pt="{ content: { class: 'max-h-[70vh] overflow-y-auto' } }"
  >
    <div class="space-y-6">
      <p class="text-sm text-surface-600 dark:text-surface-300">
        {{ t('security_help.description') }}
      </p>

      <!-- 二要素認証（2FA） -->
      <section class="space-y-4 text-sm text-surface-700 dark:text-surface-200">
        <h3 class="text-base font-semibold text-surface-900 dark:text-surface-0">
          {{ t('security_help.twofa.title') }}
        </h3>
        <p>{{ t('security_help.twofa.intro') }}</p>

        <div>
          <h4 class="mb-2 font-semibold text-surface-900 dark:text-surface-0">
            {{ t('security_help.twofa.setup_title') }}
          </h4>
          <ol class="list-decimal space-y-1 pl-5">
            <li v-for="(step, i) in twofaSetupSteps" :key="i">{{ step }}</li>
          </ol>
        </div>

        <div>
          <h4 class="mb-2 font-semibold text-surface-900 dark:text-surface-0">
            {{ t('security_help.twofa.backup_title') }}
          </h4>
          <p>{{ t('security_help.twofa.backup_desc') }}</p>
        </div>
      </section>

      <!-- アクティブセッション -->
      <section class="space-y-3 text-sm text-surface-700 dark:text-surface-200">
        <h3 class="text-base font-semibold text-surface-900 dark:text-surface-0">
          {{ t('security_help.sessions.title') }}
        </h3>
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
          <i class="pi pi-info-circle mr-1" aria-hidden="true" />{{
            t('security_help.sessions.current_note')
          }}
        </p>
      </section>

      <!-- セキュリティキー（WebAuthn） -->
      <section class="space-y-3 text-sm text-surface-700 dark:text-surface-200">
        <h3 class="text-base font-semibold text-surface-900 dark:text-surface-0">
          {{ t('security_help.webauthn.title') }}
        </h3>
        <p>{{ t('security_help.webauthn.intro') }}</p>
        <p>{{ t('security_help.webauthn.manage_desc') }}</p>
      </section>

      <!-- 身に覚えのないログインを見つけたら -->
      <section class="space-y-3 text-sm text-surface-700 dark:text-surface-200">
        <h3 class="text-base font-semibold text-surface-900 dark:text-surface-0">
          {{ t('security_help.suspicious.title') }}
        </h3>
        <p>{{ t('security_help.suspicious.intro') }}</p>
        <ol class="list-decimal space-y-1 pl-5">
          <li v-for="(step, i) in suspiciousSteps" :key="i">{{ step }}</li>
        </ol>
      </section>
    </div>
  </Dialog>
</template>
