import type {
  BatchEndpointSummary,
  BatchStatusResponse,
  BatchTriggerResponse,
} from '~/types/system-admin'

/**
 * F10.X 第三陣（丁組） — システム管理者向けバッチ起動 API クライアント。
 *
 * バックエンド: {@code /api/v1/system-admin/batch} 配下（F10.X 第二陣で実装済み）。
 * SYSTEM_ADMIN ロールでのみアクセス可能。
 *
 * 提供メソッド:
 *  - {@code listBatches()} — 登録済みバッチ一覧
 *  - {@code getStatus(name)} — 指定バッチの直近実行状況
 *  - {@code trigger(name, { sync })} — バッチ起動（非同期 / 同期）
 */
const BASE = '/api/v1/system-admin/batch'

/** trigger() 用オプション */
export interface TriggerOptions {
  /** true=同期実行、false=非同期実行（既定） */
  sync?: boolean
}

/** trigger() の結果（HTTP ステータスとレスポンス本体をセットで返す） */
export interface TriggerResult {
  /** HTTP ステータス（200=同期完了 / 202=非同期受付 / 409=ロック中 / 500=同期失敗） */
  httpStatus: number
  /** レスポンス本体（404 のみ例外でスローし、それ以外は body を返す） */
  data: BatchTriggerResponse
}

export function useSystemAdminBatchApi() {
  const api = useApi()

  /** 登録済みバッチエンドポイント一覧を取得する。 */
  async function listBatches() {
    return api<{ data: BatchEndpointSummary[] }>(BASE)
  }

  /**
   * 指定バッチの直近実行状況を取得する。
   *
   * @throws 未登録のバッチ名を指定した場合は 404 例外。
   */
  async function getStatus(name: string) {
    return api<{ data: BatchStatusResponse }>(`${BASE}/${encodeURIComponent(name)}/status`)
  }

  /**
   * 指定バッチを起動する。
   *
   * <p>HTTP ステータスごとの分岐:</p>
   * <ul>
   *   <li>200: 同期実行完了（{@code status="COMPLETED"} or {@code "FAILED"}）</li>
   *   <li>202: 非同期実行受付（{@code status="ACCEPTED"}）</li>
   *   <li>409: ロック中（{@code status="LOCKED"}）</li>
   *   <li>500: 同期実行で例外（{@code status="FAILED"}）</li>
   *   <li>404: 未登録のバッチ → 例外スロー</li>
   * </ul>
   *
   * <p>409 / 500 でも body を読み取りたいため、ofetch の {@code ignoreResponseError}
   * オプションを使い throw せずに raw レスポンスを取得する。</p>
   */
  async function trigger(name: string, options?: TriggerOptions): Promise<TriggerResult> {
    const sync = options?.sync ?? false
    const query = new URLSearchParams()
    query.set('sync', String(sync))
    const url = `${BASE}/${encodeURIComponent(name)}/trigger?${query.toString()}`

    const response = await api.raw<{ data: BatchTriggerResponse }>(url, {
      method: 'POST',
      ignoreResponseError: true,
    })

    if (response.status === 404) {
      throw new Error(`Batch endpoint not found: ${name}`)
    }

    const body = response._data
    if (!body || !body.data) {
      throw new Error(`Unexpected response from batch trigger: status=${response.status}`)
    }
    return {
      httpStatus: response.status,
      data: body.data,
    }
  }

  return {
    listBatches,
    getStatus,
    trigger,
  }
}
