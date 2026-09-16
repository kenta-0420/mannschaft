import type {
  CreateHourlyRateRequest,
  ShiftHourlyRateResponse,
} from '~/types/shift'

/**
 * 時給として送信してよい値かを判定する（CMP-260910-1555）。
 *
 * BE の `CreateHourlyRateRequest.hourlyRate` は `@NotNull @Positive` なので、
 * 0 や負数は必ず 400 になる。画面側で 0 を有効値として通すと、
 * 利用者には正しい入力に見えるのに保存時だけ汎用エラーになるため、
 * BE の契約と同じ条件をここに置いて入口で弾く。
 *
 * @param rate 入力された時給（未入力は null）
 * @returns 送信してよければ true
 */
export function isValidHourlyRate(rate: number | null): rate is number {
  return rate !== null && Number.isFinite(rate) && rate > 0
}

/** 時給入力の検証結果。null は「送信してよい」。値は i18n キーの末尾（validation.* 配下）。 */
export type HourlyRateValidationKey = 'rateRequired' | 'ratePositive'

/**
 * 時給入力を検証し、問題があれば表示すべきメッセージのキーを返す（CMP-260913-1250）。
 *
 * <h2>なぜ入力欄の min に検証を任せないか【重要】</h2>
 * 以前は `InputNumber :min="1"` でクランプしていたため、利用者が 0 を入力すると
 * フォーカスアウトの時点で欄の値が黙って ¥1 に化け、{@link isValidHourlyRate} を
 * 素通りして「時給 1 円」が新規登録されていた。弾かれたつもりの利用者に対して
 * 誤った値が無警告で保存される挙動であり、検証メッセージ `ratePositive` は
 * 到達不能なデッドコードだった。クランプを外し、入力値をそのまま保持したうえで
 * この関数で弾くことで、理由が画面に出る。
 *
 * @param rate 入力された時給（未入力は null）
 * @returns 問題が無ければ null、あれば検証メッセージのキー
 */
export function validateHourlyRate(rate: number | null): HourlyRateValidationKey | null {
  if (rate === null || !Number.isFinite(rate)) return 'rateRequired'
  if (rate <= 0) return 'ratePositive'
  return null
}

export function useShiftHourlyRateApi() {
  const api = useApi()

  async function getHourlyRate(
    teamId: string,
    userId: number,
    date?: string,
  ): Promise<ShiftHourlyRateResponse[]> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    query.set('userId', String(userId))
    if (date) query.set('date', date)
    const res = await api<{ data: ShiftHourlyRateResponse[] }>(
      `/api/v1/shifts/hourly-rate?${query.toString()}`,
    )
    return res.data
  }

  async function setHourlyRate(
    teamId: string,
    payload: CreateHourlyRateRequest,
  ): Promise<ShiftHourlyRateResponse> {
    const query = new URLSearchParams()
    query.set('teamId', String(teamId))
    const res = await api<{ data: ShiftHourlyRateResponse }>(
      `/api/v1/shifts/hourly-rate?${query.toString()}`,
      { method: 'POST', body: payload },
    )
    return res.data
  }

  return {
    getHourlyRate,
    setHourlyRate,
  }
}
