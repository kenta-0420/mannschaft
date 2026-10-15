# F18 提示モード Wake Lock 実機検証 checksheet

## 目的

顧客のレジ提示時に画面が暗転しないことを保証する。iOS Safari の制約に起因する
fallback パスの実機確認と、`error_reports` テーブルへのテレメトリ送信を検証する。

提示モード本体は `frontend/app/pages/wallet/groups/[id]/show.vue` の `acquireWakeLock()`
で実装されており、以下 4 つの context をテレメトリとして送信する。

| context | 発生条件 | 重大度 |
|---|---|---|
| `wake_lock_unsupported` | `navigator.wakeLock` が存在しない（旧 Safari など） | 通常（fallback あり） |
| `wake_lock_request_failed` | API は存在するが reject（iOS Low Power Mode / 設定拒否 など） | 通常（fallback あり） |
| `wake_lock_nosleep_fallback_ok` | nosleep.js フォールバックが成功した | 情報 |
| `wake_lock_nosleep_failed` | nosleep.js も失敗（致命傷：画面暗転の可能性） | **緊急** |

## テスト対象環境

| デバイス | OS | ブラウザ | Low Power Mode | 想定挙動 |
|---|---|---|---|---|
| iPhone 14 | iOS 17.x | Safari | OFF | Wake Lock API 取得成功 → 画面常時点灯（テレメトリは送信されない） |
| iPhone 14 | iOS 17.x | Safari | ON | reject される → nosleep.js fallback → ユーザージェスチャ後に video 再生で点灯 |
| iPhone 14 | iOS 18.x | Safari | OFF | Wake Lock API 取得成功 |
| iPhone 14 | iOS 18.x | Safari | ON | 同上 fallback |
| iPhone 14 | iOS 17/18 | Chrome (iOS) | * | Safari WebKit ベースのため Safari と同じ |
| Pixel 8 | Android 14/15 | Chrome | * | Wake Lock API 完全動作 |
| Pixel 8 | Android 14/15 | Firefox | * | （要確認。Wake Lock API は Firefox 126〜サポート） |
| iPad Pro | iPadOS 17/18 | Safari | * | Safari と同等の挙動 |

## チェック項目

各セル（環境 × Low Power 状態）で以下を確認する。

1. ✅ 提示モード起動後 5 分間、画面が暗転しない
2. ✅ スクリーンキャプチャ警告モーダルが初回のみ表示される
3. ✅ 横スワイプで前後カード切替できる
4. ✅ キーボード（外部接続時）の `←` `→` `Esc` が動作する
5. ✅ Wake Lock API 取得に成功するケース（成功時はテレメトリ送信されない）では、
      `error_reports` テーブルに `wake_lock_*` レコードが追加されない
6. ✅ Low Power Mode ON 時に `wake_lock_request_failed` context のレコードが
      `error_reports` テーブルに送信されている
7. ✅ Wake Lock API 未サポート環境（古い Safari など）で `wake_lock_unsupported`
      レコードが送信される
8. ✅ いずれのケースでも `wake_lock_nosleep_failed` context のテレメトリは
      **発生しない**（致命傷）

## 集計クエリ（SQL）

SystemAdmin が `error_reports` を以下のクエリで集計する。

```sql
SELECT
  context,
  COUNT(*)              AS total,
  COUNT(DISTINCT user_id) AS unique_users
FROM error_reports
WHERE context LIKE 'wake_lock_%'
  AND created_at >= NOW() - INTERVAL 7 DAY
GROUP BY context
ORDER BY total DESC;
```

### 期待結果（リリース後 1 週間のサンプル）

| context | 想定割合 | 解釈 |
|---|---|---|
| （テレメトリなし／API 取得成功） | 大半 | Android Chrome / 最新 iOS で Low Power OFF |
| `wake_lock_nosleep_fallback_ok` | 数 % | iOS Low Power Mode ユーザー |
| `wake_lock_request_failed` | `wake_lock_nosleep_fallback_ok` とほぼ同数 | fallback の前段として記録される |
| `wake_lock_unsupported` | 1% 未満 | 古い Safari ユーザー |
| `wake_lock_nosleep_failed` | **0 件が望ましい** | 発生時は緊急調査（後述「運用」参照） |

### userAgent ごとの内訳

```sql
SELECT
  context,
  user_agent,
  COUNT(*) AS total
FROM error_reports
WHERE context LIKE 'wake_lock_%'
  AND created_at >= NOW() - INTERVAL 7 DAY
GROUP BY context, user_agent
ORDER BY context, total DESC;
```

`user_agent` カラムは既存 `useErrorReport.captureQuiet` が自動付与する。
個人特定情報（user_id 以外の PII）は含まれていない。

## 運用

- 本 checksheet は **毎リリース前** に運用部隊が実機で実行する。
- 結果は本ドキュメント末尾「実施履歴」に追記する。
- `wake_lock_nosleep_failed` が観測された場合は **Phase 4 で追加調査・対策** を
  起こす。当該レコードの `user_agent` と `stack_trace` から原因を絞り込む。
- 集計クエリは月次の運用レポートに組み込み、トレンド（iOS バージョン分布の
  変化など）を継続的に監視する。

## 個人情報の取り扱い（GDPR）

`useErrorReport.captureQuiet` が送信する payload は以下のみ。

- `errorMessage`（コンテキスト依存の固定文字列 + エラー名）
- `stackTrace`（送信時点の JS スタック、ファイルパス含む）
- `pageUrl`（`/wallet/groups/:id/show`）
- `userAgent`（ブラウザ・OS 情報）
- `userId`（ログイン中ユーザーの内部 ID、削除済みユーザーは null）
- `context`（`wake_lock_*` のいずれか）

メールアドレス・氏名・IP・残高・カード番号などの個人特定情報は **送信されない**。

## 実施履歴

| 日付 | 実施者 | 環境 | 結果 | 備考 |
|---|---|---|---|---|
| YYYY-MM-DD | （未実施） | | | Phase 3 リリース後に初回実施 |
