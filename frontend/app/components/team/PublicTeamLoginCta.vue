<script setup lang="ts">
/**
 * F15.4 Phase 5-γ ログイン誘導 CTA カード
 *
 * 未ログインで /public/teams/[id] を閲覧したユーザーに対し、
 * ログイン / 新規登録への動線を提供する。
 *
 * - 主 CTA: ログイン (`/login?redirect=/public/teams/{teamId}`)
 * - 副 CTA: 新規登録 (`/signup?redirect=/public/teams/{teamId}`)
 *
 * リダイレクトクエリは認証後に元のページへ戻すため。
 */

const props = defineProps<{
  teamId: string
}>()

const redirectPath = computed(() => `/public/teams/${props.teamId}`)

const loginHref = computed(() => `/login?redirect=${encodeURIComponent(redirectPath.value)}`)
const signupHref = computed(() => `/register?redirect=${encodeURIComponent(redirectPath.value)}`)
</script>

<template>
  <div
    class="rounded-lg border-2 border-primary/40 bg-primary/5 p-6 shadow-sm"
    data-testid="public-team-login-cta"
  >
    <div class="flex items-start gap-3">
      <i class="pi pi-sign-in mt-1 text-2xl text-primary" aria-hidden="true" />
      <div class="flex-1">
        <h3 class="mb-2 text-lg font-semibold text-primary">
          {{ $t('publicTeamDetail.loginToSeeMore') }}
        </h3>
        <p class="mb-4 text-sm text-gray-700">
          {{ $t('publicTeamDetail.loginCtaDescription') }}
        </p>

        <div class="flex flex-wrap gap-3">
          <NuxtLink :to="loginHref">
            <Button
              :label="$t('publicTeamDetail.loginButton')"
              icon="pi pi-sign-in"
              data-testid="public-team-login-button"
            />
          </NuxtLink>
          <NuxtLink :to="signupHref">
            <Button
              :label="$t('publicTeamDetail.signupButton')"
              icon="pi pi-user-plus"
              severity="secondary"
              outlined
              data-testid="public-team-signup-button"
            />
          </NuxtLink>
        </div>
      </div>
    </div>
  </div>
</template>
