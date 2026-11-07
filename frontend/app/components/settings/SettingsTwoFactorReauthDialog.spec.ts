import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { describe, expect, it } from 'vitest'
import SettingsTwoFactorReauthDialog from './SettingsTwoFactorReauthDialog.vue'

/**
 * 本人確認ダイアログの mode ごとの契約（BE と揃える）。
 * - disable（AC-30）: パスワード保持者はパスワード＋要素の両方が必須
 * - regenerate（AC-44）: パスワードか要素のどちらかでよく、入力したものだけを送る
 */
const valueStub = (testIdFallback: string) => ({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: `<input :data-testid="$attrs['data-testid'] ?? '${testIdFallback}'" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
  inheritAttrs: false,
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  Message: { template: '<div><slot /></div>' },
  SelectButton: { props: ['modelValue', 'options'], template: '<div />' },
  Password: valueStub('password'),
  InputOtp: valueStub('otp'),
  InputText: valueStub('text'),
  Button: {
    props: ['label', 'disabled', 'type', 'loading', 'severity'],
    emits: ['click'],
    template:
      '<button :type="type ?? \'button\'" :disabled="disabled" :data-testid="$attrs[\'data-testid\']" @click="$emit(\'click\')">{{ label }}</button>',
    inheritAttrs: false,
  },
}

async function mountDialog(mode: 'disable' | 'regenerate', hasPassword: boolean) {
  const wrapper = await mountSuspended(SettingsTwoFactorReauthDialog, {
    props: { visible: true, mode, hasPassword, twoFaMethod: 'TOTP', busy: false, errorMessage: '', reauthEmail: null },
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountDialog>>

async function fill(wrapper: Wrapper, { password, code }: { password?: string; code?: string }) {
  if (password !== undefined) await wrapper.get('[data-testid="two-fa-reauth-password"]').setValue(password)
  if (code !== undefined) await wrapper.get('[data-testid="two-fa-reauth-otp"]').setValue(code)
}

function submitDisabled(wrapper: Wrapper) {
  return wrapper.get('[data-testid="two-fa-reauth-submit"]').attributes('disabled') !== undefined
}

async function submit(wrapper: Wrapper) {
  await wrapper.get('form').trigger('submit')
  return wrapper.emitted('submit')
}

describe('SettingsTwoFactorReauthDialog', () => {
  describe('regenerate（AC-44: パスワードまたは要素）', () => {
    it('パスワードだけで送れ、要素は送らない', async () => {
      const wrapper = await mountDialog('regenerate', true)
      await fill(wrapper, { password: 'pw' })
      expect(submitDisabled(wrapper)).toBe(false)
      expect(await submit(wrapper)).toEqual([[{ currentPassword: 'pw' }]])
    })

    it('要素だけで送れ、パスワードは送らない', async () => {
      const wrapper = await mountDialog('regenerate', true)
      await fill(wrapper, { code: '123456' })
      expect(submitDisabled(wrapper)).toBe(false)
      expect(await submit(wrapper)).toEqual([[{ method: 'TOTP', code: '123456' }]])
    })

    it('両方入力したら両方とも送る（BE が両方を照合する）', async () => {
      const wrapper = await mountDialog('regenerate', true)
      await fill(wrapper, { password: 'pw', code: '123456' })
      expect(await submit(wrapper)).toEqual([[{ method: 'TOTP', code: '123456', currentPassword: 'pw' }]])
    })

    it('何も入力しなければ送れない', async () => {
      const wrapper = await mountDialog('regenerate', true)
      expect(submitDisabled(wrapper)).toBe(true)
      expect(await submit(wrapper)).toBeUndefined()
    })

    it('途中まで入力したコードがあればパスワードがあっても送れない', async () => {
      const wrapper = await mountDialog('regenerate', true)
      await fill(wrapper, { password: 'pw', code: '123' })
      expect(submitDisabled(wrapper)).toBe(true)
    })

    it('パスワードを持たない利用者は要素で送れる', async () => {
      const wrapper = await mountDialog('regenerate', false)
      expect(wrapper.find('[data-testid="two-fa-reauth-password"]').exists()).toBe(false)
      await fill(wrapper, { code: '654321' })
      expect(await submit(wrapper)).toEqual([[{ method: 'TOTP', code: '654321' }]])
    })
  })

  describe('disable（AC-30: パスワード保持者はパスワード＋要素）', () => {
    it('要素だけでは送れない', async () => {
      const wrapper = await mountDialog('disable', true)
      await fill(wrapper, { code: '123456' })
      expect(submitDisabled(wrapper)).toBe(true)
    })

    it('パスワードだけでは送れない', async () => {
      const wrapper = await mountDialog('disable', true)
      await fill(wrapper, { password: 'pw' })
      expect(submitDisabled(wrapper)).toBe(true)
    })

    it('両方そろえば両方を送る', async () => {
      const wrapper = await mountDialog('disable', true)
      await fill(wrapper, { password: 'pw', code: '123456' })
      expect(submitDisabled(wrapper)).toBe(false)
      expect(await submit(wrapper)).toEqual([[{ method: 'TOTP', code: '123456', currentPassword: 'pw' }]])
    })

    it('パスワードを持たない利用者は要素だけで送れる', async () => {
      const wrapper = await mountDialog('disable', false)
      await fill(wrapper, { code: '123456' })
      expect(await submit(wrapper)).toEqual([[{ method: 'TOTP', code: '123456' }]])
    })
  })
})
