import { resolveApiBaseUrl } from '~/composables/useApiBaseUrl'

/**
 * STOMP WebSocket の接続先 URL を解決する共通ヘルパー。
 *
 * BE の STOMP エンドポイント（{@link WebSocketConfig} / {@link SignageWebSocketConfig}）は
 * いずれも `.withSockJS()` のみで登録されており、生 WebSocket のアップグレードは
 * SockJS の raw websocket transport（`{endpoint}/websocket`）でしか受け付けない。
 * bare な `{endpoint}`（例: `/ws`）へ直接 WebSocket 接続すると HTTP 400 になり、
 * stompjs の reconnectDelay により **無限再接続ループ**が発生する（実ブラウザで観測済み）。
 *
 * SockJS プロトコル自体は使わない（フレーム形式は素の STOMP のまま）。あくまで
 * ハンドシェイクのパスサフィックスだけを SockJS の raw websocket transport 規約に
 * 合わせる。
 *
 * - apiBase が絶対 URL（dev: FE/BE が別ポート）の場合:
 *   スキームを http→ws / https→wss に変換して `{endpoint}/websocket` を付加する。
 *   例: `http://localhost:8080` + `/ws` → `ws://localhost:8080/ws/websocket`
 * - apiBase が空・相対パス（本番: 同一オリジン構成）の場合:
 *   `{endpoint}/websocket` をそのまま返す（本番の挙動を変えない）。
 *
 * @param apiBase {@link resolveApiBaseUrl} の解決結果
 * @param endpoint STOMP エンドポイントのベースパス（既定 `/ws`。サイネージ等は `/ws/signage`）
 */
export function resolveWsUrl(apiBase: string, endpoint = '/ws'): string {
  const suffixed = `${endpoint}/websocket`
  if (!apiBase) return suffixed
  return apiBase.replace(/^http(s?)/, 'ws$1') + suffixed
}

/**
 * {@link useRuntimeConfig} から解決した apiBase を使って STOMP WebSocket の接続先 URL を得る。
 *
 * 呼び出し側の composable のトップレベル（同期実行時点、または WebSocket 接続確立の
 * まさにその呼び出し内）で使うこと。
 *
 * @param endpoint STOMP エンドポイントのベースパス（既定 `/ws`。サイネージ等は `/ws/signage`）
 */
export function useWsUrl(endpoint = '/ws'): string {
  const config = useRuntimeConfig()
  return resolveWsUrl(resolveApiBaseUrl(config), endpoint)
}
