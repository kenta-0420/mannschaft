// F10.6 Phase 10-γ-③-b: SSRエラーをSpring Bootに転送するNitroプラグイン
export default defineNitroPlugin((nitroApp) => {
  nitroApp.hooks.hook('error', (error, { event }) => {
    // Nitro内部エラー（404等）は除外
    if (!error || !(error instanceof Error)) return

    const config = useRuntimeConfig()
    const internalToken = config.internalLogToken || 'dev-internal-token'
    // ブラウザ用 apiBase（config.public.apiBase）は本番で '' になり得るため、
    // Nitro サーバーサイド専用の internalApiBase を使用する。
    // 設計書: docs/security/03_security_headers_and_csp.md §2.1（apiBase 二層構成）
    const apiBase = config.internalApiBase || 'http://localhost:8080'

    // PIIマスキング（メールアドレス）
    const mask = (text: string) =>
      text.replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,7}\b/g, '***@***.***')

    const payload = {
      level: 'error',
      message: mask(error.message || ''),
      stack: mask(error.stack || ''),
      path: event?.path || '',
      timestamp: new Date().toISOString(),
      userAgent: event?.headers?.get?.('user-agent') || '',
    }

    // fire-and-forget（SSRのレスポンスをブロックしない）
    $fetch(`${apiBase}/api/internal/ssr-logs`, {
      method: 'POST',
      headers: { 'X-Internal-Token': internalToken },
      body: payload,
    }).catch(() => {
      // ログ送信失敗はサイレントに無視（SSRレスポンスへの影響を避ける）
    })
  })
})
