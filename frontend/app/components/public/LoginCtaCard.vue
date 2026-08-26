<script setup lang="ts">
/**
 * F19.1 ログイン誘導 CTA カード。
 *
 * 未ログインユーザーに対し「ログイン / 新規登録」への導線を提供。
 * 投稿一覧・投稿詳細・チーム / 組織トップで広く再利用される。
 *
 * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §12.1
 */
const props = withDefaults(
  defineProps<{
    /** 「このチームに参加する」「この組織に参加する」の切り替え */
    scopeKind?: 'TEAM' | 'ORGANIZATION'
    /** 招待用のスコープ ID（signup ページにクエリで渡す） */
    scopeId?: string
  }>(),
  {
    scopeKind: 'TEAM',
    scopeId: undefined,
  },
)

const { t } = useI18n()

const joinHref = computed(() => {
  if (props.scopeKind === 'ORGANIZATION' && props.scopeId !== undefined) {
    return `/register?inviteOrg=${props.scopeId}`
  }
  if (props.scopeKind === 'TEAM' && props.scopeId !== undefined) {
    return `/register?inviteTeam=${props.scopeId}`
  }
  return '/register'
})

const joinLabel = computed(() =>
  props.scopeKind === 'ORGANIZATION' ? t('public.cta.joinOrganization') : t('public.cta.join'),
)
</script>

<template>
  <Card
    class="border border-primary/20 bg-primary/5"
    data-testid="public-login-cta"
  >
    <template #content>
      <div class="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h3 class="text-lg font-bold text-surface-900 dark:text-surface-50">
            {{ t('public.cta.ctaHeading') }}
          </h3>
          <p class="mt-1 text-sm text-surface-600 dark:text-surface-300">
            {{ t('public.cta.ctaDescription') }}
          </p>
        </div>
        <div class="flex shrink-0 flex-wrap gap-2">
          <NuxtLink :to="joinHref">
            <Button :label="joinLabel" />
          </NuxtLink>
          <NuxtLink to="/login">
            <Button :label="t('public.cta.login')" severity="secondary" outlined />
          </NuxtLink>
        </div>
      </div>
    </template>
  </Card>
</template>
