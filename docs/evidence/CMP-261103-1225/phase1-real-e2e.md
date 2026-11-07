# CMP-261103-1225 Phase1（N+1 解消 5PR）実機E2E 結果報告

- 実施日: 2026-11-05
- 対象: main `48f6a0a389`（#4277 #4278 #4280 #4288 #4294 をすべて含む）
- spec: `frontend/tests/e2e/real/n1p1-regression.real.spec.ts`（専用設定 `frontend/playwright-n1p1.config.ts`）
- 確認の観点: 今回の5PRは SQL を一括取得に置き換えただけなので、画面に出る内容と見える範囲は修正前と同じであること。各画面で「2件以上が正しく出る」「0件のとき空表示で壊れない」を確かめ、ロール横断（組織 ADMIN／同組織 MEMBER／他組織の利用者）も確かめた。

## 実行環境

| 項目 | 値 |
|---|---|
| BE | 検証用 :8081。WSL ネイティブの共有 clone（`48f6a0a389`）を `scripts/verification-run.mjs` 経由で `bootJar` → `java -jar --server.address=::` |
| FE | 検証用 :3017（この worktree の Nuxt dev。`--host 127.0.0.1`、`NUXT_PUBLIC_API_BASE=http://localhost:8081`、`NUXT_INTERNAL_API_BASE=http://127.0.0.1:8081`） |
| DB / Valkey / MinIO | 本陣共有の `mannschaft-mysql` / `mannschaft-valkey` / `mannschaft-minio` |
| ブラウザ | Playwright Chromium。`localhost` は `127.0.0.1` に解決させる |
| モック | なし（`page.route` 不使用） |

- **FE を :3017 にした理由**: :3001 は別セッションの検証用 FE（e2e-august worktree）が使っていた。BE の CORS 許可は起動時に `MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3017,...` で与えた。
- **画面が検証用 BE を叩いていることの確認**: 各テストでブラウザが出した `/api/v1/**` の宛先を集め、`http://localhost:8081` だけであることを検査している。例外は CSP 違反レポート（`/api/v1/security/csp-reports`）で、これは FE 自身（Nitro）が受ける仕様なので宛先判定から外し、内容をログに残した。

## 結果

最終実行の結果は **23 passed（7.7分）**。内訳は T12 の「既存欠陥を `test.fail` で明示した1件」を含めて全件が期待どおりで、Phase1 による表示の回帰は **0件** だった。後始末は 8件すべて 204 を確認した。

| ID | 対象PR | 内容 | 視点 | 結果 |
|---|---|---|---|---|
| T01 | #4277 | 今日の時間割: 未リンクのコマ2件が 500 にならず表示される | MEMBER1 | PASS |
| T02 | #4277 | 今日の時間割: 時間割の無い利用者は空表示 | MEMBER2 | PASS |
| T03 | #4288 | 個人ダッシュボード: 直近の予定2件が出る／業務アラートは出ない（未読確認は T03b へ分離） | MEMBER1（負の視点） | PASS |
| T03b | #4288 | 個人ダッシュボード: 未読（チャット）2件が出る | 未読確認専用 READER | PASS |
| T04 | #4288 | 個人ダッシュボード: 予定0件・未読0件は空表示 | 他組織 | PASS |
| T05 | #4288 | 業務アラート（個人ダッシュボード）: 出る・0件は空表示 | 組織/チーム ADMIN（正の視点） | PASS |
| T06 | #4288 | チームのスコープウィジェット: 予定2件・チャットが出る | MEMBER1 | PASS |
| T07 | #4288 | チームのスコープウィジェット: 予定0件のチームは空表示 | ADMIN | PASS |
| T08 | #4288 | チャット: 送信者名（2人）・添付が出る | MEMBER1 | PASS |
| T09 | #4288 | チャット: 発言0件のチャンネルは空のまま壊れない | MEMBER1 | PASS |
| T10 | #4288 | チャット: 一覧に出ず、`?channel=` の直打ちでも開けない | 他組織 | PASS |
| T11 | #4280 | プロジェクト一覧: 2件と件数（1/2・0/0）、詳細のマイルストーン | MEMBER1 | PASS |
| T12 | （既存） | プロジェクト一覧の進捗率は 50% と出るべき | MEMBER1 | 想定どおりの失敗（`test.fail`）。既存欠陥1 |
| T13 | #4280 | プロジェクト一覧: 0件のチームは空表示 | ADMIN | PASS |
| T14 | #4280 | プロジェクト: 一覧・詳細の URL 直打ちとも中身が出ない | 他組織 | PASS |
| T15 | #4294 | 組織メンバー一覧: 3人と管理操作が出る | 組織 ADMIN（正の視点） | PASS |
| T16 | #4294 | 組織メンバー一覧: 管理操作が出ない | 同組織 MEMBER（負の視点） | PASS |
| T17 | #4294 | 組織メンバー一覧: URL 直打ちでメンバーが出ない | 他組織 | PASS |
| T18 | #4294 | 組織メンバー一覧: メンバー1人の組織が壊れず出る | 他組織（自組織B） | PASS |
| T19 | #4294 | チーム検索: 2件と人数（3人/1人）、該当なしは空表示 | MEMBER2 | PASS |
| T20 | #4288 | 業務アラート（組織の管理者レンズ）: 管理者に切り替えると出る | 組織 ADMIN（正の視点） | PASS |
| T21 | #4288 | 管理者レンズ・業務アラートが出ず、管理コンソールの URL 直打ちは組織トップへ戻される | 同組織 MEMBER（負の視点） | PASS |
| T22 | #4288 | 管理コンソールの URL 直打ちで管理画面が出ない | 他組織 | PASS |
| T23 | — | 環境の証拠（検証 BE :8081 を叩いている） | — | PASS |

- **負の視点で出た画面と文言**:
  - 同組織 MEMBER: 組織タブに「管理者表示の切り替え」（メンバー/管理者のトグル）が出ない。`/organizations/{orgA}/admin` を直打ちすると `/organizations/{orgA}` に戻される（`admin-console` ミドルウェア）。
  - 他組織の利用者: `/organizations/{orgA}/admin` を直打ちすると `/organizations/{orgA}` の公開表示に戻され、「あなたはこの組織のメンバーではないため、一部のウィジェットは非表示になっています」と出る。
  - 他組織の利用者: `/chat?channel={T1 のチャンネル}` を直打ちすると「指定されたチャンネルにアクセスできません」と出る。

## 検分指摘（P2×3）への対応と再実行

PR #4302 への検分指摘3件に対応し、検証環境を立て直して再実行した。

1. **通常設定でも spec が拾われていた**: `playwright.config.ts` の `chromium-real` がこの spec を拾い、専用の globalSetup が走らないまま `readWorld()` が ENOENT で落ちていた。
   - 対応: 同設定の `testIgnore` に追加した（`playwright-real.config.ts` は追加済み）。
   - 確認: ほかの `playwright*.config.ts` は、`testMatch` がそれぞれの対象 spec に限られており、この spec を拾わないことを grep で確かめた。
   - あわせて、`readWorld()` は前提データが無いとき、専用設定から実行するよう促す例外を投げるようにした。
2. **空チャンネルのテストで応答を取り逃しうる**: 遷移を終えたあとに応答待ちを登録していたため、速い環境では応答を取り逃していた。
   - 対応: path を省いて開き、`waitApi` の action の中で遷移するようにした。
   - 確認: 同じ形のほかの箇所も点検した。アコーディオンの開閉と応答待ちを組む箇所は、待ちを登録したあとにクリックしており、応答はクリック後のマウントで初めて発生する。管理者レンズの箇所も action の中でクリックしている。
3. **予定の時刻が固定（JST 22:00/23:00）だった**: 夜に走らせると予定が過去になり、偽の失敗になる。
   - 予定: 実行時刻の **3時間後・4時間後** に開始する相対時刻に変えた。画面側は「今週（7日間）」と「今後の予定」で見るので、日付をまたいでも外れない。
   - 今日の時間割: **全曜日** に同じ2コマを置き、適用開始を **前日** にした。実行中に日付をまたいでも「今日」にコマがある。
   - T03 の名称は「今日の予定」から「直近の予定」に改めた。

**再実行の結果**: 検証環境（BE :8081・FE :3017）を立て直し、全23件を流して **23 passed（10.4分）** だった。T12 の既存欠陥 `test.fail` を含め、すべて期待どおりで、後始末8件もすべて 204 を確認した。

- 予定は実行時刻から相対（3〜4時間後）で作ったうえで、影響するテストがすべて PASS した。対象は T03（直近の予定）、T06・T07（スコープウィジェット）、T01・T02（今日の時間割）、T09（空チャンネル）。
- `--list` の結果:

  | 設定 | この spec の件数 |
  |---|---|
  | `playwright.config.ts` | 0件 |
  | `playwright-real.config.ts` | 0件 |
  | `playwright-n1p1.config.ts` | 23件 |

## 再検分指摘（P2×2）への対応と再実行

1. **負の視点で、認可判定の完了を待たずに非表示を確かめていた**
   - 問題: `gotoAndHydrate` が待つのはマウントだけだった。API が遅いと、読み込み中に `toHaveCount(0)` が即座に成功し、後から他テナントの情報が出る回帰を見逃しうる。
   - 対応: 判定が終わった証拠を `waitForResponse` で受け取り（遷移より前に登録）、そのあとで非表示を確かめる形に直した。対象は次の6件（固定の `waitForTimeout(5_000)` も廃止）。

     | テスト | 判定完了の証拠 |
     |---|---|
     | T03（MEMBER1、業務アラートが出ない） | `GET /me/teams` が 200 で、全チームのロールが MEMBER。そのうえで業務アラートの表示も `GET /admin/business-alerts/summary` の呼び出しも無いことを確認 |
     | T10（他組織、チャット） | `GET /chat/channels` が 200、`GET /chat/channels/{id}` が **403**、拒否の文言が出る |
     | T14（他組織、プロジェクト） | 一覧 `GET /teams/{slug}/projects` が **403**、詳細 `GET /teams/{slug}/projects/{id}` が **403** |
     | T16（同組織 MEMBER、組織メンバー一覧） | メンバー一覧が 200、`me/permissions` が 200（roleName=MEMBER）、3人の表示名が表示済み。そのうえで管理操作が無いことを確認 |
     | T17（他組織、組織メンバー一覧） | `GET /organizations/{slug}/members` が **403** |
     | T21（同組織 MEMBER、組織の管理者レンズ） | 組織の `me/permissions` が 200 で roleName=MEMBER。そのうえでレンズ・業務アラートが無いことを確認 |

   - 確認だけで変更しなかったもの:
     - T04（空表示）、T22（拒否の文言の表示）: もともと API 応答後にしか出ない表示を待っている。
     - T15: 3人の表示を確認したあとで、他組織の利用者がいないことを見ている。
2. **未読確認がテスト間で状態を共有していた**
   - 問題: チャットの閲覧テスト（T08）が同じチャンネルを既読にするため、実行順や `--repeat-each` しだいで未読確認が落ちていた。
   - 対応: globalSetup で、未読確認専用の利用者 READER・チーム T3・チャンネル（ADMIN の発言2件）を用意した。
     - T03 から未読確認を外し、新しいテスト「未読（チャット）が出る（READER）」に分けた。
     - 確認内容: `unread-threads` の `total_unread_chat` が 2 で、「チャット／未読メッセージ」の行に 2 が出る。READER はどのテストでもチャットを開かない。
     - T3 は名前に tag を含めず、チーム検索の期待値に混ざらないようにした。
   - ほかに共有データの状態を変えるテストが無いかも点検した。
     - チャットを開いて既読にするのは T08・T09 だけで、対象は MEMBER1 のチャンネル。
     - 管理者レンズの切り替えとアコーディオンの開閉は、ブラウザ文脈内の状態。
     - チーム検索の「サポーターになる」は押していない。
     - いずれもほかのテストの期待値に影響しない。

**再実行の結果**: 検証環境を立て直し、全24件（T03b を追加）を `--repeat-each=2` で流した。結果は **48 passed（16.8分）**。

- 2回分とも T12 の既存欠陥 `test.fail` は想定どおりの失敗で、それ以外の失敗は無かった。
- 後始末は、途中で止めた手直し中の回の残りも含めて **20件すべて 204** で削除できた。

認可判定の実測値は次のとおり。

| 他組織の利用者がした要求 | 応答 |
|---|---|
| `GET /chat/channels/{id}` | 403 |
| `GET /teams/{t1}/projects` | 403 |
| `GET /teams/{t1}/projects/{id}` | 403 |
| `GET /organizations/{orgA}/members` | 403 |

同組織 MEMBER の組織 `me/permissions` は roleName=MEMBER だった。

## 対象操作の UI 証拠

| テスト | 開始 URL・入口 | 操作要素 | 最終表示・API 応答 | API の補助用途 |
|---|---|---|---|---|
| T01 | `/dashboard`（MEMBER1） | 「予定・時間」区分の開閉ボタン `#personal-dashboard-section-button-schedule` | `GET /me/dashboard/timetable-today` が 200。「今日の時間割」に未リンクのコマ2件（科目名）が出て、「時間割の取得に失敗しました」は出ない | ログイン、前提作成（個人時間割） |
| T02 | `/dashboard`（MEMBER2） | 同上 | 200。「今日の時間割はありません」 | ログイン |
| T03 | `/dashboard`（MEMBER1） | 「予定・時間」の開閉 | `/me/teams` が 200（全て MEMBER）、`upcoming-events` が 200。T1 の予定2件が出て、「業務アラート」の表示も summary API の呼び出しも無い | ログイン、前提作成（予定） |
| T03b | `/dashboard`（READER） | 「連絡・お知らせ」の開閉 | `unread-threads` が 200 で `total_unread_chat`=2。「チャット／未読メッセージ」の行に 2 | ログイン、前提作成（専用チャンネル・発言） |
| T04 | `/dashboard`（他組織） | 同上 | 「今週の予定はありません」「未読はありません」。T1 の予定は出ない | ログイン |
| T05 | `/dashboard`（ADMIN） | 画面を開くだけ | `GET /admin/business-alerts/summary` が 200。「業務アラート」「アラートはありません」 | ログイン |
| T06 | `/dashboard` のチームタブ（MEMBER1） | `scope-segment-TEAM`、`scope-tab-chip-TEAM-{t1}` | `swipe-widget-grid-TEAM` に予定2件とチャンネル名が出る | ログイン |
| T07 | `/dashboard` のチームタブ（ADMIN・T2） | `scope-tab-chip-TEAM-{t2}` | 「今後の予定はありません」「チャットはありません」 | ログイン |
| T08 | `/chat`（MEMBER1） | チャンネル一覧のチャンネル名をクリック | `GET /chat/channels/{id}/messages` が 200。3件の `chat-message` に送信者（ADMIN／MEMBER1 の表示名）が出て、添付ファイル名が出る。「不明」は出ない | ログイン、前提作成（発言・MinIO への添付アップロード） |
| T09 | `/chat?channel={空}`（MEMBER1） | URL のクエリで自動オープン（応答待ちを登録してから遷移） | 200。入力欄 `team-chat-input` が出て、`chat-message` は0件 | ログイン |
| T10 | `/chat?channel={T1}`（他組織） | URL 直打ち | 「指定されたチャンネルにアクセスできません」。発言・チャンネル名は出ない | ログイン |
| T11 | `/teams/{t1}/projects`（MEMBER1） | カードのタイトルをクリックして詳細へ | 一覧の API が 200。2件が出て、「1/2 タスク」と「0/0 タスク」。詳細にマイルストーン名が出る | ログイン、前提作成（プロジェクト・TODO・マイルストーン） |
| T12 | 同上 | なし | **既存欠陥**: 進捗率が「5000%」と出る（下記「欠陥」）。`test.fail` で明示 | ログイン |
| T13 | `/teams/{t2}/projects`（ADMIN） | なし | 「プロジェクトがありません」 | ログイン |
| T14 | `/teams/{t1}/projects` と `/projects/{id}`（他組織） | URL 直打ち | 一覧にも詳細にもプロジェクト名・マイルストーン名が出ない | ログイン |
| T15 | `/organizations/{orgA}/members`（ADMIN） | 画面を開くだけ | `GET /organizations/{slug}/members` が 200。3人の表示名と削除ボタン `member-remove-{id}` が出る。他組織の利用者は出ない | ログイン、前提作成（招待トークンで参加） |
| T16 | 同上（MEMBER1） | なし | 削除・権限グループの操作が出ない | ログイン |
| T17 | 同上（他組織） | URL 直打ち | 組織Aのメンバー名が出ない | ログイン |
| T18 | `/organizations/{orgB}/members`（他組織＝自分の組織） | なし | 1人（自分）だけが壊れずに出る | ログイン |
| T19 | `/teams/search`（MEMBER2） | キーワード入力 → 「検索」ボタン、続けて該当なしの語で再検索 | 検索 API が 200。T1「3人」、T2「1人」のカード。該当なしでは「該当するチームが見つかりませんでした」 | ログイン |
| T20 | `/dashboard` の組織タブ（ADMIN） | 「管理者表示の切り替え」の「管理者」 | `GET /dashboard/organization/{slug}/admin-business-alert` が 200。`admin-alert-total-ORGANIZATION` が出る | ログイン |
| T21 | 同上（MEMBER1）→ `/organizations/{orgA}/admin` を直打ち | 組織タブ | 「管理者表示の切り替え」も業務アラートも出ない。直打ちすると `/organizations/{orgA}` に戻される | ログイン |
| T22 | `/organizations/{orgA}/admin`（他組織） | URL 直打ち | `/organizations/{orgA}` に戻され、「あなたはこの組織のメンバーではない」旨が出る。業務アラートは出ない | ログイン |

- 前提データはすべて globalSetup で API から作った（利用者4人を新規登録し、メール認証の代わりに `users.status` だけを直接更新）。
- 後始末は globalTeardown で行い、時間割・チャンネル・チーム・組織を逆順に削除して 204 を確認した。
- 手直しの途中で中断した回の残りデータ（組織・チーム・チャンネル・時間割）も、DB で列挙して記録し直したうえで、同じ後始末で 204 まで削除した。
- 登録した使い捨て利用者（`e2e-n1p1-*@test.mannschaft.local`）は、既存の実機 spec と同じく残している（退会 API の対象外）。

## 欠陥

### 1. 既存欠陥: プロジェクト一覧の進捗率が100倍で表示される（Phase1 の回帰ではない）

- **再現手順**:
  1. チームにプロジェクトを作り、TODO を2件紐付けて1件を完了にする。
  2. `/teams/{slug}/projects` を開く。
  3. 「1/2 タスク」のカードに **5000%** と出る（正しくは 50%）。
- **原因の推定**:
  - BE の `ProjectRepository.recalculateProgress`（`backend/src/main/java/com/mannschaft/app/todo/repository/ProjectRepository.java:63` 付近）が、`progress_rate` を `* 100.0` した 0〜100 の百分率で保存している。
  - FE の `frontend/app/pages/teams/[slug]/projects/index.vue:138`（表示）と `:141`（ProgressBar の値）が `Math.round(project.progressRate * 100)` で、さらに100倍している。
- **#4280 の回帰でない根拠**: #4280 は `entity.getProgressRate()` をそのまま応答に渡しており、値の意味を変えていない。FE の `* 100` は 2026-06 のリネーム（147244aaf3）以前からある。
- 修正はしていない。spec では `test.fail` で明示しており、直ると赤になって知らせる。

### 2. 既存欠陥の疑い: 同じ秒に投稿したチャットの表示順が投稿順にならない（Phase1 の回帰ではない）

- **観測**: 同じ秒に投稿された2件（id 140 → 141、どちらも `created_at` が 03:18:35）が、画面では 141 → 140 の順に出た。
- **原因の推定**: チャンネルのメッセージ一覧の取得順が `created_at` だけで、`id` による同順位の決め方が無い。
  - `backend/src/main/java/com/mannschaft/app/chat/repository/ChatMessageRepository.java:33`（`findByChannelIdOrderByCreatedAtDesc`）
  - 同 `:39`（`ORDER BY m.createdAt DESC`）
  - `chat_messages.created_at` は秒精度の `datetime`。
- **回帰でない根拠**: #4288 の `enrichMessages` は受け取った順のまま添付・リアクションを付けるだけで、並び順を変えていない。
- spec では順序を検証していない。修正はしていない。

### 3. 観察（欠陥と断定せず）: ダッシュボードで script-src の CSP 違反レポートが出る

- `/dashboard` のチームタブ・組織タブで、`script-src` の CSP 違反レポートが FE に送られた。
- 開発サーバー（Nuxt dev / Vite）固有の可能性が高く、N+1 置換とは無関係と判断した。本番ビルドでは確認していない。

## 未実施と理由

| 対象 | 理由 |
|---|---|
| #4277 村フィード（`GET /me/village-feed`） | 本体ウィジェット `DashboardVillageFeedWidget.vue` はどこにもマウントされていない。同じ API を使う `WidgetVillageLobbyDigest.vue` もピン一覧しか使わない。村の作成はシステム管理者限定のため、今回は扱っていない。画面から確かめられる導線が無い |
| #4278 ペイウォール（FREE プランの有料機能判定） | 発生元の `EntitlementGuard.require()` を呼ぶ箇所が BE に無く、402 `ENTITLEMENT_003` が出ない。`PaywallModal` を開く経路が実機に存在しない |
| #4278 支払要件（会費。`GET /me/payment-requirements`） | この API を呼ぶ FE 画面が無い。同じ処理は後見まとめ払い（`/me/guardianship/bulk-payment`、`GET /me/payable-dues`）にしか出ず、保護者と子の関係を作る必要があるため今回は扱っていない |
| #4288 チャットハブ・チャットフォルダ（`/dashboard/chat-hub`、`/chat-folders`） | composable はあるが、呼ぶページが無い |
| #4294 `/members/all`（配下全メンバー） | 専用ページが無い。FE から使っているのは `ActivityEditDialog.vue` だけで、今回は扱っていない |
| #4288 業務アラートの件数が2件以上の表示 | 件数を増やすには、予約モジュールを有効にした予約、または問い合わせチャンネルの未読が要る。今回は0件の表示と、ロールごとの出し分けまでにとどめた |
| #4294 友好チーム数（`teamFriendCount`） | API は返しているが、チーム検索の画面に出していない（相互フォローの前提データは作った） |

## 環境で踏んだ罠（再発防止）

- `scripts/verification-run.mjs` は Windows 側 worktree（`.git` ファイルが Windows パスを指す）を source にできない。WSL ネイティブの `git clone --shared` を source にした。
- 共有 clone の `checkout` と `git archive` は、`/mnt/c` 上の object を読むため各十数分かかる。
  - 再実行時は、GitHub から対象 SHA だけを `git fetch --depth 1` したネイティブ clone を source にした（数十秒で済んだ）。
- BE に `MANNSCHAFT_R2_ACCESS_KEY` と `MANNSCHAFT_R2_SECRET_KEY`（ローカル MinIO は minioadmin）を渡さないと、チャット添付の presign が `STORAGE_004`（500）になる。
- :8081 は WSL 側の待受のため、Windows から `[::1]:8081` では繋がらない。FE は `--host 127.0.0.1`、SSR 側の向き先は `127.0.0.1:8081` にし、ブラウザは `localhost` を `127.0.0.1` に解決させた。
- 個人ダッシュボードのウィジェットは、アコーディオンの区分を開くまでマウントされない（API も呼ばれない）。
- Playwright は実行開始時に `test-results/` を消す。前提データの記録は `tests/e2e/.auth/n1p1-*.json`（gitignore 済み）に置いた。
