<script setup lang="ts">
import dayjs from 'dayjs'
import { useForm } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { z } from 'zod'
import { resolveCountry } from '~/utils/resolveCountry'

definePageMeta({
  layout: 'auth',
  middleware: 'guest',
})

const { t } = useI18n()
const api = useApi()
const { getGoogleAuthUrl } = useAuthApi()
const route = useRoute()
const notification = useNotification()
const { userTimezone } = useDatetime()
const loading = ref(false)
const googleLoading = ref(false)
const termsModalVisible = ref(false)
const privacyModalVisible = ref(false)

async function registerWithGoogle() {
  googleLoading.value = true
  try {
    const res = await getGoogleAuthUrl()
    window.location.href = res.data.authUrl
  } catch {
    notification.error(t('auth.oauth.callback_error'))
    googleLoading.value = false
  }
}

// クエリパラメータから招待トークンを取得（ベータ制限対応）
const inviteToken = computed(() => route.query.invite as string | undefined)

// 郵便番号バリデーション（レジストリ駆動）
// 登録画面では locale='ja' 固定のため実効国 = JP
const { ensureLoaded, isSupported, validateFormat } = usePostalCodeValidation()

// ページマウント時にポリシーを先読みしておく（フォームサブミット時の遅延を最小化）
onMounted(() => {
  ensureLoaded().catch(() => {
    // 取得失敗はサイレント（BE が authoritative なので保存時に 400 が返る）
  })
})

const schema = toTypedSchema(
  z.object({
    email: z
      .string()
      .min(1, 'メールアドレスは必須です')
      .email('有効なメールアドレスを入力してください'),
    password: z
      .string()
      .min(8, 'パスワードは8文字以上で入力してください')
      .refine((val) => {
        let count = 0
        if (/[A-Z]/.test(val)) count++
        if (/[a-z]/.test(val)) count++
        if (/[0-9]/.test(val)) count++
        if (/[^A-Za-z0-9]/.test(val)) count++
        return count >= 3
      }, '大文字・小文字・数字・記号のうち3種以上含めてください'),
    lastName: z.string().min(1, '姓は必須です').max(50, '姓は50文字以内で入力してください'),
    firstName: z.string().min(1, '名は必須です').max(50, '名は50文字以内で入力してください'),
    displayName: z
      .string()
      .min(1, '表示名は必須です')
      .max(50, '表示名は50文字以内で入力してください'),
    postalCode: z
      .string()
      .superRefine(async (val, ctx) => {
        // レジストリ駆動郵便番号検証（ハードコード regex を廃止）
        // 登録画面は locale='ja' 固定 → 実効国 JP
        const effectiveCountry = resolveCountry(null, 'ja')
        if (!effectiveCountry) return // 解決不能は検証スキップ

        // ポリシーをロード（既にキャッシュ済みの場合は即返る）
        await ensureLoaded()

        if (!isSupported(effectiveCountry)) return // 未対応国は検証スキップ

        // 必須チェック
        if (!val || val.length === 0) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: '郵便番号は必須です',
          })
          return
        }

        // フォーマットチェック（BE の pattern を使用）
        if (!validateFormat(effectiveCountry, val)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: '郵便番号の形式が正しくありません（例: 123-4567）',
          })
        }
      }),
    birthDate: z
      .string()
      .min(1, 'parental_consent.error_auth_050')
      .regex(/^\d{4}-\d{2}-\d{2}$/, 'parental_consent.error_auth_051')
      .refine((val) => {
        const d = new Date(val)
        return !isNaN(d.getTime()) && d <= new Date()
      }, 'parental_consent.error_auth_052')
      .refine((val) => {
        const d = new Date(val)
        const limit = new Date()
        limit.setFullYear(limit.getFullYear() - 100)
        return d >= limit
      }, 'parental_consent.error_auth_053'),
    privacyPolicyAccepted: z.literal(true, {
      errorMap: () => ({ message: 'auth.register.privacy_consent_required' }),
    }),
    termsAccepted: z.literal(true, {
      errorMap: () => ({ message: 'auth.register.terms_consent_required' }),
    }),
  }),
)

const { defineField, handleSubmit, errors } = useForm({
  validationSchema: schema,
  initialValues: {
    email: '',
    password: '',
    lastName: '',
    firstName: '',
    displayName: '',
    postalCode: '',
    birthDate: '',
    privacyPolicyAccepted: false as unknown as true,
    termsAccepted: false as unknown as true,
  },
})

const [email, emailProps] = defineField('email')
const [password, passwordProps] = defineField('password')
const [lastName, lastNameProps] = defineField('lastName')
const [firstName, firstNameProps] = defineField('firstName')
const [displayName, displayNameProps] = defineField('displayName')
const [postalCode, postalCodeProps] = defineField('postalCode')
const [birthDate, birthDateProps] = defineField('birthDate')
const [privacyPolicyAccepted, privacyPolicyAcceptedProps] = defineField('privacyPolicyAccepted')
const [termsAccepted, termsAcceptedProps] = defineField('termsAccepted')

const submitted = ref(false)

const onSubmit = handleSubmit(async (values) => {
  submitted.value = true
  loading.value = true
  try {
    await api('/api/v1/auth/register', {
      method: 'POST',
      body: {
        email: values.email,
        password: values.password,
        lastName: values.lastName,
        firstName: values.firstName,
        nickname: values.displayName,
        postalCode: values.postalCode,
        birth_date: values.birthDate,
        locale: 'ja',
        timezone: 'Asia/Tokyo',
        privacyPolicyAccepted: true,
        privacyPolicyVersion: '1.1.0',
        ...(inviteToken.value ? { inviteToken: inviteToken.value } : {}),
      },
    })
    notification.success('登録が完了しました。メールをご確認ください。')
    navigateTo(`/verify-email?email=${encodeURIComponent(values.email)}`)
  } catch (e: unknown) {
    const err = e as { data?: { error?: { code?: string; message?: string } } }
    const code = err?.data?.error?.code
    const message = err?.data?.error?.message || 'しばらく時間をおいて再度お試しください。'
    if (code === 'AUTH_041') {
      notification.error('このメールアドレスは使用できません', message + '　→ ログインして退会を取り消してください。')
    } else if (code === 'AUTH_042') {
      notification.error(t('auth.beta_invite_required'), t('auth.beta_invite_required_desc'))
    } else if (code === 'AUTH_043') {
      notification.error(t('auth.beta_invite_invalid'), t('auth.beta_invite_invalid_desc'))
    } else {
      notification.error('登録に失敗しました', message)
    }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <form
    novalidate
    @submit.prevent="submitted = true; onSubmit()"
  >
    <div class="flex flex-col gap-4">
      <!-- 戻るリンク -->
      <NuxtLink
        to="/"
        class="inline-flex items-center gap-2 text-sm text-surface-500 transition-colors hover:text-primary"
      >
        {{ $t('landing.features_detail.back_to_top') }}
      </NuxtLink>

      <!-- Google 登録ボタン -->
      <Button
        type="button"
        :label="$t('auth.oauth.google_register')"
        icon="pi pi-google"
        severity="secondary"
        outlined
        class="w-full"
        :loading="googleLoading"
        @click="registerWithGoogle"
      />

      <!-- セパレーター -->
      <div class="flex items-center gap-3">
        <div class="flex-1 border-t border-surface-200 dark:border-surface-600" />
        <span class="text-sm text-surface-400">{{ $t('auth.oauth.or') }}</span>
        <div class="flex-1 border-t border-surface-200 dark:border-surface-600" />
      </div>

      <div class="flex flex-col gap-2">
        <label for="email">メールアドレス <span class="text-red-500">※</span></label>
        <InputText
          id="email"
          v-model="email"
          v-bind="emailProps"
          type="email"
          placeholder="example@mannschaft.app"
          :invalid="submitted && !!errors.email"
        />
        <small v-if="submitted && errors.email" class="text-red-500">{{ errors.email }}</small>
      </div>

      <div class="flex flex-col gap-2">
        <label for="password">パスワード <span class="text-red-500">※</span></label>
        <Password
          v-model="password"
          input-id="password"
          v-bind="passwordProps"
          :feedback="true"
          toggle-mask
          fluid
          :invalid="submitted && !!errors.password"
        />
        <small v-if="submitted && errors.password" class="text-red-500">{{
          errors.password
        }}</small>
        <small class="text-surface-500">
          8文字以上、大文字・小文字・数字・記号のうち3種以上を含めてください。
        </small>
        <small class="text-surface-400">パスワードは暗号化された状態で保存されます。</small>
      </div>

      <div class="flex flex-col gap-2">
        <label for="postalCode">郵便番号 <span class="text-red-500">※</span></label>
        <InputText
          id="postalCode"
          v-model="postalCode"
          v-bind="postalCodeProps"
          placeholder="123-4567"
          :invalid="submitted && !!errors.postalCode"
        />
        <small v-if="submitted && errors.postalCode" class="text-red-500">{{
          errors.postalCode
        }}</small>
        <small class="text-surface-400">郵便番号は暗号化された状態で保存されます。</small>
      </div>

      <div class="flex flex-col gap-2">
        <label for="birthDate">{{ $t('parental_consent.birth_date_label') }} <span class="text-red-500">※</span></label>
        <InputText
          id="birthDate"
          v-model="birthDate"
          v-bind="birthDateProps"
          type="date"
          :max="dayjs().tz(userTimezone).format('YYYY-MM-DD')"
          class="w-full"
          :invalid="submitted && !!errors.birthDate"
        />
        <small v-if="submitted && errors.birthDate" class="text-red-500">{{ $t(errors.birthDate) }}</small>
      </div>

      <div class="flex gap-4">
        <div class="flex min-w-0 flex-1 flex-col gap-2">
          <label for="lastName">姓 <span class="text-red-500">※</span></label>
          <InputText
            id="lastName"
            v-model="lastName"
            v-bind="lastNameProps"
            placeholder="山田"
            :invalid="submitted && !!errors.lastName"
            class="w-full"
          />
          <small v-if="submitted && errors.lastName" class="text-red-500">{{
            errors.lastName
          }}</small>
        </div>
        <div class="flex min-w-0 flex-1 flex-col gap-2">
          <label for="firstName">名 <span class="text-red-500">※</span></label>
          <InputText
            id="firstName"
            v-model="firstName"
            v-bind="firstNameProps"
            placeholder="太郎"
            :invalid="submitted && !!errors.firstName"
            class="w-full"
          />
          <small v-if="submitted && errors.firstName" class="text-red-500">{{
            errors.firstName
          }}</small>
        </div>
      </div>

      <div class="flex flex-col gap-2">
        <label for="displayName">表示名 <span class="text-red-500">※</span></label>
        <InputText
          id="displayName"
          v-model="displayName"
          v-bind="displayNameProps"
          placeholder="yamada_taro"
          :invalid="submitted && !!errors.displayName"
        />
        <small v-if="submitted && errors.displayName" class="text-red-500">{{
          errors.displayName
        }}</small>
      </div>

      <div class="flex flex-col gap-1">
        <div class="flex items-start gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700">
          <Checkbox
            id="termsAccepted"
            v-model="termsAccepted"
            v-bind="termsAcceptedProps"
            :binary="true"
            :invalid="submitted && !!errors.termsAccepted"
          />
          <label for="termsAccepted" class="cursor-pointer select-none text-sm leading-relaxed">
            <button
              type="button"
              class="font-medium text-primary hover:underline"
              @click.stop.prevent="termsModalVisible = true"
            >{{ $t('landing.legal.terms.title') }}</button>{{ $t('auth.register.privacy_consent_suffix') }}
          </label>
        </div>
        <small v-if="submitted && errors.termsAccepted" class="text-red-500">
          {{ $t(errors.termsAccepted) }}
        </small>
      </div>

      <div class="flex flex-col gap-1">
        <div class="flex items-start gap-3 rounded-lg border border-surface-200 p-3 dark:border-surface-700">
          <Checkbox
            id="privacyPolicyAccepted"
            v-model="privacyPolicyAccepted"
            v-bind="privacyPolicyAcceptedProps"
            :binary="true"
            :invalid="submitted && !!errors.privacyPolicyAccepted"
          />
          <label for="privacyPolicyAccepted" class="cursor-pointer select-none text-sm leading-relaxed">
            <button
              type="button"
              class="font-medium text-primary hover:underline"
              @click.stop.prevent="privacyModalVisible = true"
            >{{ $t('landing.legal.privacy.title') }}</button>{{ $t('auth.register.privacy_consent_suffix') }}
          </label>
        </div>
        <small v-if="submitted && errors.privacyPolicyAccepted" class="text-red-500">
          {{ $t(errors.privacyPolicyAccepted) }}
        </small>
      </div>

      <Button
        type="submit"
        label="アカウント作成"
        icon="pi pi-user-plus"
        :loading="loading"
        class="mt-2"
      />

      <div class="text-center">
        <NuxtLink to="/login" class="text-sm text-primary hover:underline">
          すでにアカウントをお持ちですか？
        </NuxtLink>
      </div>
    </div>

    <TermsModal v-model:visible="termsModalVisible" />
    <PrivacyModal v-model:visible="privacyModalVisible" />
  </form>
</template>
