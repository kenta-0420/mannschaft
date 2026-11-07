# 足軽A 実機差し戻し・編集タイムゾーン修正

- 修正source: `73d9bd8e2cb6c7e46d279dcdfa5c68d5c2c34f63`
- 修正前source: `27a05e045f460c755ac8026c488f5d0387ff43a1`
- production変更: `frontend/app/components/schedule/ScheduleEventForm.vue` のみ。
- ほかの変更: `ScheduleEventFormInitialDateTime.spec.ts` に8件追加、F02.2仕様へ編集日時復元を追記。
- 実行環境: WSL native `/tmp/dashboard-fe-unit-261024/frontend`、Node22.23.2/npm10.9.8。

## 実機の根拠と原因

実機担当が採取したPOST201・個人詳細GET200の双方で、timeは `2026-10-12T09:00:00+09:00` から `2026-10-12T10:00:00+09:00`。利用者設定はAsia/Tokyo、ブラウザはAmerica/Los_Angelesで、編集表示は前日10/11の17:00から18:00へずれていた。対象は所有cleanup済みの個人予定id14。

個人・共有の両編集分岐でAPI値を `new Date(iso)` へ渡し、その端末ローカル成分をDatePickerと時刻欄へ復元していたことが原因。既存の `toUserZonedParts` を用いた共通復元へ揃えた。helperはオフセット付き値をユーザーTZへ変換し、オフセット無しの旧API値をユーザーTZの壁時計として解釈する。秒・ミリ秒をDateへ保持し、秒を含む時刻選択肢、終日終了の排他→包含変換を維持する。

リマインダー・予約タスク・繰り返し終了日等の付随値の復元には変更を加えていない。

## 実行結果

| 検証 | 結果 | 終了コード | 証跡 |
|---|---|---|---|
| 修正前の実red | 個人/team/orgの時刻付き3件と終日3件、計6件が日時assertで失敗 | 1（意図した失敗） | red.log |
| 修正後のフォーム関連全spec | 2spec、69件合格、skip0 | 0 | green.log |
| 対象lint | 修正フォームと日時specの2ファイル | 0 | eslint.log（診断なし） |
| 修正後Nuxt型検査 | `NODE_OPTIONS=--max-old-space-size=8192 npm run typecheck` | 0 | typecheck.log |

redは上記の正確なAPI日時で実行した。終日予定のAPI終了は翌日00:00+09:00とし、日付ピッカーへ当日を包含終了として戻す契約も失敗を確認した。red時の7skipは `-t` フィルターで既存テストを除外したもの。greenでは除外せず全件実行した。

greenの内訳は既存フォーム54件と日時15件。日時specは以前の7件に、実機TZ回帰6件と「秒保持／オフセット無し壁時計」の2件を加えた。既存のnaive値・秒・watcher自動補正・終日排他終了の回帰も含む。

戦役全体の足軽A担当分は、前回残る9spec58件と今回2spec69件を合わせ **11spec127件** が合格。前回119件から8件追加であり、追加数は6件ではない。前回証跡は `../worker-calendar-88aa2695/summary.md` を保持している。

型検査sourceは修正commit73d9bd8。E2E担当が所有する新規Playwrightファイルは本nativeコピー・本型検査に含めていない。実機担当へproduction1ファイルの同期可と修正commitを通知済み。最終E2E結果は別担当の証跡に従う。

再現コマンドはruntime/の各scriptへ保存。既存の重複auto-import警告、authStoreを簡略化した既存specの初期化警告はログに保持し、終了コードと実assertの合格を確認している。
