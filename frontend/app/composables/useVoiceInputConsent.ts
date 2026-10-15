import type { VoiceInputConsentResponse } from '~/types/quickMemo'

const CURRENT_VERSION = 1

export function useVoiceInputConsent() {
  const api = useApi()

  async function getActiveConsent(version = CURRENT_VERSION) {
    return api<{ data: VoiceInputConsentResponse }>(
      `/api/v1/me/voice-input-consents/active?version=${version}`,
    )
  }

  async function grantConsent(version = CURRENT_VERSION) {
    return api<{ data: VoiceInputConsentResponse }>('/api/v1/me/voice-input-consents', {
      method: 'POST',
      body: { version },
    })
  }

  async function revokeConsent() {
    return api('/api/v1/me/voice-input-consents/active', { method: 'DELETE' })
  }

  return { getActiveConsent, grantConsent, revokeConsent, CURRENT_VERSION }
}
