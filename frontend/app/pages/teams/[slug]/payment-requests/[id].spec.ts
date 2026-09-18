import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(
  resolve(process.cwd(), 'app/pages/teams/[slug]/payment-requests/[id].vue'),
  'utf8',
)

describe('協会請求詳細・支払いページ', () => {
  it('詳細GETでVIEWED遷移をBEへ委ね、数値team IDを使う', () => {
    expect(source).toContain("resolveScopeId('TEAM', teamSlug.value)")
    expect(source).toContain('getTeamPaymentRequest(teamId.value, requestId.value)')
  })

  it('支払い開始の二重送信を防ぎ、Stripe確認後だけpollを始める', () => {
    expect(source).toContain('if (!teamId.value || !canPay.value || startingPayment.value) return')
    expect(source).toContain('@confirmed="startPolling"')
    expect(source).toContain("request.value = { ...request.value, status: 'PROCESSING' }")
  })

  it('1秒間隔・最大20回でPAID、失敗復帰、timeoutを分岐する', () => {
    expect(source).toContain('const POLL_INTERVAL_MS = 1000')
    expect(source).toContain('const MAX_POLL_ATTEMPTS = 20')
    expect(source).toContain("updated?.status === 'PAID'")
    expect(source).toContain("updated.status !== 'PROCESSING'")
    expect(source).toContain('clearPaymentRequestIdempotencyKey(teamId.value, requestId.value)')
    expect(source).toContain('attempt >= MAX_POLL_ATTEMPTS')
  })

  it('アンマウント時にタイマーを停止する', () => {
    expect(source).toContain('onUnmounted(() =>')
    expect(source).toContain('unmounted = true')
    expect(source).toContain('stopPolling()')
  })

  it('PROCESSINGの短期poll中も同じIdempotency-Keyで支払いを再開できる', () => {
    expect(source).toContain(':loading="startingPayment"')
    expect(source).toContain('@click="canPay ? startPayment() : resumePayment()"')
    expect(source).not.toContain(':loading="actionBusy"')
  })
})
