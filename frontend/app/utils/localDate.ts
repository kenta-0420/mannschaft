/**
 * BE の {@code LocalDate}（`yyyy-MM-dd`）へ送る日付文字列を組み立てるユーティリティ。
 *
 * ## なぜ必要か（Issue #2508 ②「日付が 1 日ずれる」）
 *
 * `Date.prototype.toISOString()` は必ず **UTC 基準** で文字列化するため、
 * `toISOString().slice(0, 10)` は「ユーザーがカレンダー上で指した日」ではなく
 * 「その瞬間の UTC 上の日付」を返してしまう。
 *
 * ```
 * 2026-07-29 00:00 JST → toISOString() → "2026-07-28T15:00:00.000Z" → slice(0,10) → "2026-07-28"
 * ```
 *
 * UTC より東（日本を含む）では **前日**、UTC より西（`America/*`）では夕方以降に **翌日** が
 * 送信される。BE 側は `LocalDate` で受けるうえ、Jackson の `LocalDateDeserializer` は
 * `"...T...Z"` 形式を寛容に切り詰めて受理してしまうため、400 にもならず**静かに 1 日ずれた日付が保存される**。
 *
 * ## タイムゾーンの基準について（設計判断）
 *
 * 本関数は **ブラウザのローカル壁時計**（`Date#getFullYear()` / `getMonth()` / `getDate()`）を基準とし、
 * `users.timezone`（`useDatetime()` が表示に使うユーザー設定 TZ）への変換は **行わない**。理由:
 *
 * 1. `LocalDate` は「タイムゾーンを持たない暦上の日付」であり、変換すべき瞬間（instant）ではない。
 *    余計な TZ 変換を挟むと、変換のたびに日付がずれる余地を作るだけで、得るものがない。
 * 2. DatePicker はブラウザ TZ でカレンダーを描画し「今日」をハイライトする。ユーザーがクリックしたセルの
 *    年月日は `Date` のローカル壁時計成分そのものなので、これを取り出すのが唯一の無損失（WYSIWYG）な経路。
 *    ここで `users.timezone` へ変換すると、出張中などブラウザ TZ とプロフィール TZ が食い違うユーザーで
 *    「画面で選んだ日と違う日が送信される」という、いま直そうとしているのと同じ症状を再生産してしまう。
 * 3. 既存の {@link ~/utils/activityFields#toYmd} も同じ方針（ローカル壁時計）で実装済みで、実績がある。
 *
 * ## 時刻を伴う `OffsetDateTime` 系との関係（Issue #2508 Phase 2 で訂正）
 *
 * 以前ここには「`OffsetDateTime` 系は瞬間の変換が必要なので `users.timezone` 基準」と書かれていたが、
 * **これは誤りであり、この一文が実際にバグを容認していた**。`useDatetime().buildOffsetDateTimeStr()` は
 * ピッカーが返す `Date` を `dayjs(date).tz(users.timezone)` で投影し直しており、ブラウザ TZ と
 * プロフィール TZ が食い違うユーザーで上記 2. とまったく同じ「選んだ日が 1 日ずれる」症状を出していた。
 *
 * 正しい原則は日付・日時で共通である:
 *
 * > **ピッカー由来の `Date` は「瞬間」ではなく「ユーザーが画面で指した壁時計」である。**
 * > 年月日（および時分秒）はローカル壁時計成分として取り出し、`users.timezone` は
 * > **投影ではなく、その壁時計に付けるオフセットの決定にのみ**使う。
 *
 * したがって `LocalDate` は本関数、`OffsetDateTime` は
 * `useDatetime().buildOffsetDateTimeStr()`（内部で本関数を使い壁時計成分を取り出す）を使うこと。
 * 暦日が文字列で先に決まっている範囲検索は `buildDayStartStr` / `buildDayEndStr` を使う。
 * `yyyy-MM-dd` を作る処理を新規に書き起こしてはならない（同じバグを再生産する）。
 */

/**
 * `Date` をローカル壁時計基準の `yyyy-MM-dd` 文字列へ変換する。
 *
 * BE の `LocalDate` フィールド / `@RequestParam LocalDate` へ送る値はすべてこれを通すこと。
 * `toISOString().slice(0, 10)` は UTC 日付になるため使ってはならない。
 *
 * @param date 変換対象（DatePicker が返すローカル 0:00 の `Date` を想定）
 * @returns `yyyy-MM-dd` 形式のローカル日付
 * @throws {RangeError} `date` が Invalid Date の場合（症状を隠さず失敗させる）
 *
 * @example toLocalDateString(new Date(2026, 6, 29)) // JST → "2026-07-29"
 */
export function toLocalDateString(date: Date): string {
  if (Number.isNaN(date.getTime())) {
    throw new RangeError('toLocalDateString: Invalid Date は yyyy-MM-dd に変換できません')
  }
  const y = String(date.getFullYear()).padStart(4, '0')
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}
