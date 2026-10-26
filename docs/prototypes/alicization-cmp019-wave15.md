# CMP-019 Wave 15 実機・アリシゼーション（2026-09-28）

- 対象: Issue #3495 / PR #3496。募集ペナルティの再計算解除を本人へ通知する。
- 環境: 実バックエンド `localhost:8081`、既存フロントエンド `localhost:3003`、実 MySQL、Playwright Chromium。フロントエンドのアプリコードはこの Wave で変更していない。
- 確認範囲: アプリ内通知と既存 Web Push 経路。ローカル環境に VAPID 鍵がないため、実際の Web Push 送信は未確認。

## 実機 E2E

`frontend/tests/e2e/real/cmp-019-wave15-penalty-recompute-notification.real.spec.ts` を実 BE/FE/MySQL で実行し、**1 passed**。専用の有効ペナルティを再計算バッチで `DISPUTE_REVOKED` に解除し、DB と本人 API に NORMAL 通知が1件だけできることを確認した。通知本文に解除理由があり、別ユーザーの一覧には出ない。直後の再実行は ShedLock により409となり、通知は増えなかった。本人の `/notifications` 画面にもタイトルと本文が表示された。試験が作成したペナルティ、設定、通知は `finally` で ID を限定して削除した。

最初の試行は画面表示の直前に実機バックエンドの起動セッションが終了し、UI の通知取得が失敗した。監視セッションと同じテスト専用暗号鍵を使って再実行し、上記の green を得た。実装由来の失敗として扱っていない。

## 3住民の自由探索

E2E の後片付けと独立させるため、探索用に本人の通知 `3255966` を同じ種類・文面で専用 DB に仮挿入した。以下の UI・認可探索はこの仮通知による。バッチから実際に通知を作る経路は上記 E2E で別途確認した。

| 住民 | 観測 |
| --- | --- |
| 本人・一般会員 | [PC 1440×900](evidence/cmp019-wave15/member-desktop-1440-before.png) と [モバイル 390×844](evidence/cmp019-wave15/member-mobile-390-before.png) でタイトルと `再計算（DISPUTE_REVOKED）により募集ペナルティが解除されました。` を確認。両画面の既読 API は200。モバイルで横はみ出しと文字の重なりなし。押下直後の画像には未読ドットが残り、その後の描画反映は未確認。[操作結果](evidence/cmp019-wave15/member-result.json)。 |
| 対象外ユーザー | 通知一覧 API は200だが対象 ID なし。ID 指定 GET は404 `COMMON_005`、既読 POST は404 `NOTIFICATION_001`。`/notifications` の UI は読み込み表示が続いた。ID を URL に直打ちした画面は404。[証跡](evidence/cmp019-wave15/outsider/report.json)。 |
| システム管理者 | 通知一覧 API は200で対象 ID なし。ID 指定 GET・既読 POST は両方404。30秒後の [通知画面](evidence/cmp019-wave15/admin/notifications-30s.png) は表示され、通知取得 API は200、対象タイトルは出ず、HTTP エラーなし。[観測ログ](evidence/cmp019-wave15/admin/probe-result.json)。 |

## 後片付けと残る確認

専用ユーザー `990510`〜`990521`、探索用通知、試験用ペナルティ・設定・通知、認証トークン、メール出力等を ID 限定で削除した。DB 再照合でユーザー・通知・ペナルティ・設定・監査ログ・確認トークン・役割・天気設定はすべて0件。一時保存したテスト専用暗号鍵も削除した。

画面の既読ドットがクリック直後に残る点と、対象外ユーザーの一覧が読み込み表示に留まる点は、この探索だけでは原因を確定できない。本人への通知作成・表示と認可境界の受け入れ条件は実機 E2E と API・DB の照合で確認した。
