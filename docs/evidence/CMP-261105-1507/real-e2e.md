# CMP-261105-1507（N+1 戦役で見つかった既存不具合6件）実機E2E 結果報告

- 実施日: 2026-11-07
- 対象: #4306（FE）・#4311（BE）・#4324（BE）がすべて入った main
- spec: `frontend/tests/e2e/real/cmp1507-regression.real.spec.ts`（専用設定 `frontend/playwright-cmp1507.config.ts`、前提データは `helpers/cmp1507-global-setup.ts`、後始末は n1p1 と共通の globalTeardown）
- あわせて既存の `n1p1-regression.real.spec.ts`（24件）を新しい main に対して全件流した（T12 は `test.fail` なしで通ること）。

## 実行環境

| 項目 | 値 |
|---|---|
| BE | 検証用 :8081。`origin/main`（`412aad1613`）を WSL ネイティブへ `git clone --depth 1` し、`scripts/verification-run.mjs` 経由で `bootJar` → systemd ユーザーユニット `n1p1-be-8081`（`java -jar --server.address=::`）。CORS は `MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3017,...` で与え、起動後に OPTIONS で `Access-Control-Allow-Origin: http://localhost:3017` を実測 |
| FE | 検証用 :3017。この worktree（`40ba2cc8cd`、#4306・#4311・#4324 を含む）の Nuxt dev（Windows、Node 22.23.2）。`NUXT_PUBLIC_API_BASE=http://localhost:8081`、`NUXT_INTERNAL_API_BASE=http://127.0.0.1:8081` |
| DB / Valkey / MinIO | 本陣共有の `mannschaft-mysql` / `mannschaft-valkey` / `mannschaft-minio` |
| ブラウザ | Playwright Chromium。`--host-resolver-rules=MAP localhost 127.0.0.1` |
| モック | なし（`page.route` 不使用）。API はログイン・前提データ作成・後始末と、画面に導線が無い前提操作（時間割の作成と削除）にだけ使用 |

画面が検証 BE を叩いていることは、各テストでブラウザが出した `/api/v1/**` の宛先が `http://localhost:8081` だけであることを検査して確かめた（CSP 違反レポートの送信先は FE 自身の受け口なので除外）。

## 結果の要約

| ID | 内容 | 視点 | 結果 |
|---|---|---|---|
| ① | プロジェクト一覧・詳細の進捗率が 50%（5000% でない） | MEMBER | PASS |
| ⑤ | 詳細の「関連タスク」が行（タイトル・状態・期限・担当者）で出る。JSON 生データではない。完了は取り消し線 | MEMBER | PASS |
| ⑥ | 詳細の「タスク 1/2」「マイルストーン 1/2」が出る（以前は「/」だけ） | MEMBER | PASS |
| ② | 続けて投稿した 8 件が投稿順に並ぶ（投稿直後・再読込後の履歴とも）。実測で 8 件中 4〜5 件が同じ秒に重なった | MEMBER | PASS |
| ③ | 学期作成: 年度を空にして送ると BE が 400（`fieldErrors: academicYear 必須項目です`）で、作成されない。画面は汎用トースト（下記「新しい欠陥」1） | ADMIN | BE は PASS ／ 画面の出し方に欠陥 |
| ③ | 年度を入れて送ると 201 で作成され、一覧に出る（画面に並び順の入力欄は無く、FE は 0 を送る） | ADMIN | PASS |
| ④ | 時間割が生きている学期の削除は 409（`TIMETABLE_022`）で消えない | ADMIN | BE は PASS ／ 画面の出し方に欠陥（下記 2） |
| ④ | 時間割を削除（論理削除）した後も、その学期の削除は 409 で消えない（500 にならない） | ADMIN | BE は PASS |
| ④ | 時間割の無い学期は削除でき（204）、一覧から消える | ADMIN | PASS |
| 権限なし | 学期画面: 「学期追加」ボタンも削除列も出ない（一覧は読める） | MEMBER | PASS |
| 他テナント | プロジェクト詳細 URL 直打ち: 403 で中身が出ない／チャンネル `?channel=` 直打ち: 403 と「指定されたチャンネルにアクセスできません」／学期一覧 API: 403 で中身が出ない | 他組織 | PASS |
| n1p1 24件 | 既存 spec。T12（①）は `test.fail` なしで PASS | 各視点 | 23件 PASS＋T01 は再実行で PASS（下記） |

- cmp1507 spec の最終実行: **7 passed（2.5分）**。
- n1p1 spec: 初回は 23 passed・1 failed。失敗の T01（今日の時間割）は `/dashboard` を初めて開く Nuxt dev の初回コンパイルが 300 秒のテスト時間切れになったもの（`page.goto: net::ERR_ABORTED`）。FE が温まったあとに単独で再実行して **PASS（32.5秒）**。後始末はどの実行でも 204（または既に無い 404）。

## 操作した要素・最終画面

| テスト | 開始 URL | 操作要素 | 最終画面・応答 |
|---|---|---|---|
| ①⑤⑥ | `/teams/{t1}/projects`（MEMBER） | カードのタイトルをクリックして詳細へ | 一覧: 「1/2 タスク」カードに「50%」。詳細: 進捗「50%」、タスク「1/2」、マイルストーン「1/2」。関連タスク 2 行（完了行は取り消し線のリンク、期限 2030-04-01、担当者のニックネーム）。`{` `"` `statusLabel` などの生データは出ない |
| ①⑤⑥ 他テナント | `/teams/{t1}/projects/{id}`（他組織）直打ち | なし | `GET .../projects/{id}` が 403。プロジェクト名・TODO名・マイルストーン名は出ない |
| ② | `/chat?channel={id}`（MEMBER） | 入力欄 `team-chat-input` に 8 件を 1 件ずつ入力して Enter（入力欄が空になるのを待って次へ）。その後ページを再読込 | 8 件が `chat-message` に投稿順（上から古い順）で連続して並ぶ。`createdAt` は秒単位で `…:41 :41 :42 :42 :42 :43 :43 :43` のように重なっていた。再読込後（履歴取得）も同じ並び |
| ② 他テナント | `/chat?channel={id}`（他組織）直打ち | なし | `GET /chat/channels/{id}` が 403。「指定されたチャンネルにアクセスできません」。入力欄・投稿は出ない |
| ③ | `/organizations/{orgA}/timetable`（ADMIN） | タブ「学期テンプレート」→「学期追加」→ 学期名・開始日・終了日を入力、年度を全消去 →「学期追加」 | `POST .../timetable-terms` が 400（`COMMON_001`、`fieldErrors: [{field: academicYear, message: 必須項目です}]`）。ダイアログは開いたまま。右上にトースト「学期の作成に失敗しました」。年度を `2031` に入れ直して送ると 201 で、一覧に学期が出て「学期を作成しました」 |
| ④ | 同上（ADMIN） | 学期を 2 件追加。1 件に時間割を作成（API）。ごみ箱ボタン（`.pi-trash`） | (a) 時間割あり: `DELETE /timetable-terms/{id}` が 409（`TIMETABLE_022`「時間割が紐づいているため学期を削除できません」）。行は残り、トーストは `common.dialog.error`（下記 2）。(b) 時間割を API で削除したあと同じ学期を再度削除: やはり 409。(c) 時間割の無い学期: 204 で行が消える |
| 権限なし | `/organizations/{orgA}/timetable`（MEMBER） | タブ「学期テンプレート」 | `GET .../timetable-terms` が 200 で学期名は読める。「学期追加」ボタンも削除列も出ない |
| 他テナント | 同上（他組織）直打ち | なし | `GET .../timetable-terms` が 403。学期名・追加ボタンは出ない |

### ロール3視点（まとめ）

- **権限あり（ADMIN）**: 学期の追加・削除ができる。チームのプロジェクト・チャットも操作できる。
- **権限なし（同組織 MEMBER）**: プロジェクト詳細・チャットは見て使える。学期は読めるが、追加・削除の導線は出ない。
  - URL 直打ちでも画面は読み取り専用のまま。API 直叩きで MEMBER の削除が弾かれることは、今回は確かめていない（API は前提・後始末にだけ使う方針）。
- **他テナント**: プロジェクト詳細・チャンネル・学期一覧のいずれも 403 で、中身は出ない。

## 新しい欠陥（観測事実）

1. **学期追加の画面が BE の検証エラーを出さない**
   - 現象: 年度を空にして送ると、BE は `fieldErrors: [{academicYear: 必須項目です}]` を返す。画面は欄ごとの表示を出さず、汎用トースト「学期の作成に失敗しました」だけを出す。
   - 補足: 年度欄に必須の印が無い。年度が空でも「学期追加」ボタンは押せる（無効化は学期名・開始日・終了日が空のときだけ）。
   - 影響: ③「画面にバリデーションエラーが出る」は満たされていない。BE の修正（500 でなく 400）は効いている。
   - 場所: `frontend/app/pages/organizations/[slug]/timetable.vue`（`submitTerm`）。
2. **学期削除が失敗したとき、トーストに翻訳キーがそのまま出る**
   - 現象: 時間割が残る学期の削除（409）で、トーストの文言が `common.dialog.error`（翻訳されていない生のキー）。409 専用の文言でもない。
   - 原因の手がかり: `deleteTerm` は `t('common.dialog.error')` を引くが、`locales/ja/common.json` では `dialog` が `common` の下ではなく最上位にある。
   - 影響: ④「409 相当のメッセージが画面に出る」は満たされていない。BE の修正（500 でなく 409）は効いている。
3. **学期テンプレートの表見出しに翻訳キーがそのまま出る**: 管理者の表の見出しが `common.label.actions`（生のキー）。
4. **チームの時間割作成で学期を選べない疑い**（画面では未確認）
   - API 観測: FE の `listTerms('team', slug)` が呼ぶ `GET /api/v1/teams/{slug}/timetable-terms` は、`organizationId` を付けないため 400 を返す（API で確認）。
   - FE 側: `openCreateDialog` はその失敗を握りつぶしている。
   - 補足: チームの時間割画面には、学期を追加・削除する導線も、時間割を削除する導線も無い。今回の ④ の前提（時間割の作成・削除）は API で行った。
5. **投稿の送信中に押した Enter は捨てられる**（軽微・観測のみ）: `ChatMessageInput` は送信中の Enter を無視し、入力欄にはその文言が残る。投稿が画面に出た直後に次の文言を入れて Enter を押すと、送られないことがあった（spec は入力欄が空になるのを待ってから次を入力する）。

## 後始末と残骸

- spec が作った組織・チーム・チャンネル・時間割・時間割の無い学期は、globalTeardown で 204（または既に無い 404）まで削除した。
- **残骸の timetable_terms id=4**（親チーム削除済み）を API で削除できるか試した。
  - 結果: 検証用アカウント（その学期の持ち主ではない）での `DELETE /api/v1/timetable-terms/4` は **403 `COMMON_002`（この操作を行う権限がありません）**。
  - 持ち主のアカウントが分からず、DB は触っていないため、削除できていない。
- **今回新たに残る学期**: 「時間割が残る学期は削除できない」（#4324 の仕様どおり）ため、④ の前提で作った学期は消せない。
  - 論理削除済みの時間割が残る学期が 4 件（id 5・6・9・12 と推定）。事前の API 探索で作った 2 件（5・6）と、spec の ④ で作った分（実行ごとに 1 件）。
  - spec の途中失敗で記録前に親組織だけが消えた、時間割の無い学期が 1 件（id 7 と推定）。これも親が消えており、持ち主でないと 403 になる見込み。
  - 登録した使い捨て利用者（`e2e-n1p1-*@test.mannschaft.local`）は、既存の実機 spec と同じく残る（退会 API の対象外）。
- 今後 ④ を流すたびに学期が 1 件残る。恒久的に流すなら、BE 側にテスト用の後始末手段が要る。

## 未実施と理由

| 対象 | 理由 |
|---|---|
| #4311 のカーソル対象が論理削除済みのときの履歴取得・再接続時の回収 | 画面から「カーソル対象を論理削除した状態で履歴を取り直す」導線が無い。BE のテストの領分 |
| ①⑤⑥ の `/my/projects`・`/organizations/{slug}/projects` | 同じ部品を使う。今回はチームのプロジェクトで確かめ、個人・組織の画面は開いていない |
| ③ の「並び順（sortOrder）を空で送れば作成できる」を画面から | 画面に並び順の入力欄が無い。FE は常に 0 を送る（画面からの作成は 201）。API 直で「sortOrder 省略 → 201」「academicYear 省略 → 400」は事前に実測した |
| 同組織 MEMBER が学期を API 直叩きで削除した場合 | API は前提・後始末にだけ使う方針のため |

## 環境で踏んだ罠（再発防止）

- **MinIO が公開ポートを失っていた**: `mannschaft-minio` が `mannschaft_default` ネットワークから外れ、ポート公開が空になっていた（Windows からも WSL からも 9000 に繋がらず、チャット添付の前提作成が `fetch failed` になった）。`docker network connect mannschaft_default mannschaft-minio` のあと `docker restart mannschaft-minio` で復旧した。
- **Nuxt dev の初回起動に約15分かかる**: `Vite server built` の後、Nitro が出来上がるまで :3017 は接続できても応答しない。さらに各ページの初回コンパイルが数分かかり、先頭のテストが時間切れになりうる。主要ページへ `curl` を一度当てて温めてから流すとよい。
- **WSL VM が切れると systemd ユニットも消える**: `wsl -e sleep infinity` を常駐させた状態で `systemd-run --user` を使った。
- `scripts/verification-run.mjs` の中の `./gradlew` は実行権限が無いため `bash ./gradlew` で呼んだ。systemd ユニットの環境には `node` が無く、nvm のパスを足した。
- WSL の npm（nvm 配下の 2 つ）は `graceful-fs` が欠けて壊れていた。FE は WSL でなく Windows の worktree で立てた。
- worktree の `node_modules` が無かったため `npm ci`（約 37 分）で作った（検証用 FE のためで、コミットには入れない）。

---

# 追記: 最新 main での再確認（2026-11-09）

- 対象 main: `dced3a9312e12ff1a7ac8c3d8005c542cdd312df`（#4355・#4358・#4375 を含む。前回の `412aad1613` から更新）
- spec: `frontend/tests/e2e/real/cmp1507-latest-main.real.spec.ts`（新規 11 件）と、`cmp1507-regression.real.spec.ts` の ③④ を修正後の画面に合わせて更新（設定 `playwright-cmp1507.config.ts` が両方を流す）。
- 最終実行: **cmp1507 設定 18 件すべて PASS（17.5 分）**。後始末は 204（時間割が付いて消せない学期を除く。下記）。

## 実行環境

| 項目 | 値 |
|---|---|
| BE | 検証用 :8081。`dced3a9312` を WSL ネイティブへ `git fetch --depth 1` し、`scripts/verification-run.mjs` 経由で `bootJar`（`-Pmax.parallel.forks=1`）→ systemd ユーザーユニット `n1p1-be-8081`。鍵は本陣と同じ設定、CORS は `http://localhost:3017` を OPTIONS で実測（`Access-Control-Allow-Origin: http://localhost:3017`） |
| FE | 検証用 :3017。この worktree（ブランチは `origin/main` から切り直し）の Nuxt dev（Windows、Node 22.23.2）。止めて起動し直し、主要 14 ページへ `curl` して温めた（初回の Nitro 構築のあいだ `/` が 503 を返し、全体で約 25 分） |
| ブラウザ | Playwright Chromium、`--host-resolver-rules=MAP localhost 127.0.0.1` |
| モック | なし。API はログイン・前提データ作成・後始末と、他人の発言・DM・使い捨てチームのモジュール ON・アカウント言語（de）の設定にだけ使用。確かめる操作はすべて画面 |

前提は、使い捨てチーム（ASCII 名、時間割モジュール ON）に、ADMIN（作成者）・MEMBER・第三の MEMBER を入れ、別組織の利用者（OUTSIDER）はどこにも入れない構成。

## 結果（既存 spec）

| 対象 | 結果 | 根拠 |
|---|---|---|
| cmp1507 ①⑤⑥（プロジェクト進捗・関連タスク・件数。MEMBER／他テナント） | PASS | 前回と同じ。他組織は詳細 URL 直打ちで 403 |
| cmp1507 ②（チャットの並び） | PASS | 8 件中 4 件が同じ秒。投稿直後・再読込後とも投稿順 |
| cmp1507 ③④（学期の作成・削除） | PASS（spec を更新） | 前回の欠陥 1・2・3 が直ったため、期待値を修正後の画面に更新した（下記。失敗を隠したのではなく、直った挙動に合わせた） |
| n1p1 24 件 | 21 件 PASS・3 件失敗 → 再実行で 3 件 PASS（計 24 件 PASS） | 初回の失敗は、#4288 チームのスコープウィジェット（`swipe-widget-grid-TEAM` が 60 秒以内に出ない）、#4294 組織メンバー一覧 ADMIN（`waitForResponse` 90 秒切れ）、#4288 業務アラート負の視点（管理コンソール URL が組織トップへ戻る前の 60 秒切れ）。いずれも初回コンパイル直後の時間切れの疑いで、温まったあとに 3 件だけ再実行して PASS（9.8 分）。時間切れの原因（初回コンパイルか否か）までは追っていない。T12（①進捗 50%）は `test.fail` なしで PASS。T01 は初回から PASS |

③④ の更新内容:
- ③: 年度を空にすると、送信前に欄のエラー「年度を入力してください」が出て、POST は飛ばない。年度を入れれば 201。
- ④: 時間割が残る学期の削除は、確認ダイアログのあとで 409（`TIMETABLE_022`）になり、専用の文言「時間割が残っているため、この学期は削除できません。先に時間割を整理してください」のトーストが出る（以前は翻訳キー `common.dialog.error` が出ていた）。時間割を論理削除したあとも 409。確認でキャンセルすると消えない。時間割の無い学期は 204 で消える。

## 結果（新規 spec。ID は `cmp1507-latest-main.real.spec.ts`）

| 修正 | 内容 | 視点 | 結果 |
|---|---|---|---|
| #4355 | マイルストーンを追加すると、入力したタイトルで保存される（リクエスト本文の `title` と一覧の表示が一致）。更新も入力したタイトルで保存され、再読込しても残る | ADMIN | PASS |
| #4355 | プロジェクトの編集（タイトル変更）が保存され、再読込しても残る | ADMIN | PASS |
| #4355 | de で、一覧・詳細・マイルストーン追加ダイアログ・プロジェクト編集ダイアログに日本語の行が 0 件 | ADMIN | PASS |
| #4355 | MEMBER には「作成」「編集」「鉛筆」が出ない。他組織は URL 直打ちで 403、タイトルもマイルストーンも出ない | MEMBER／他組織 | PASS |
| #4358 | サイドバー（ハンバーガー → スケジュール）に「時間割」が出て、クリックで `/teams/{slug}/timetable` へ辿れる（モジュール ON） | ADMIN／MEMBER | PASS |
| #4358 | 年度に「27」を入れて送ると、2000 に丸められず欄のエラー「年度は2000〜2100の範囲で入力してください」。入力欄の値は 27 のまま、POST は 0 件 | ADMIN | PASS |
| #4358 | 開始日を終了日より後にすると、500 でなく欄のエラー「終了日は開始日より後の日付にしてください」。POST は 0 件 | ADMIN | PASS |
| #4358 | 正しい日付で学期を追加（201、年度は 2036 で送られる）→ その学期で時間割を作成（201）→ 一覧タブで DRAFT（下書き）を削除（確認でキャンセルすると残り、承認で 204） | ADMIN | PASS |
| #4358 | 学期の削除は確認つき（キャンセルで残る）。時間割（論理削除済み）が付いた学期は 409 と専用文言。時間割の無い学期（年度は全角「２０３７」で入力→半角に正規化）は 204 で消える | ADMIN | PASS |
| #4358 | MEMBER: 学期は読める（200）が、「学期追加」・削除ボタン・「時間割作成」・時間割の削除ボタンが出ない。サイドバーには時間割が出る | MEMBER | PASS |
| #4358 | 他組織: URL 直打ちで `GET /teams/{t2}/timetables` が 403。学期名・追加ボタン・時間割作成・サイドバーの時間割リンクが出ない | 他組織 | PASS |
| #4375 | 自分の投稿を、⋯メニューの「編集」→ ダイアログで保存（PATCH 200、「(編集済み)」が付く、再読込後も編集後の本文）。ホバーのごみ箱で削除（削除済み表示）。他人（ADMIN）の投稿には削除が出ない | MEMBER | PASS |
| #4375 | 未読 3 件のチャンネルを開くと（`POST /read` 成功）、一覧の未読バッジが消える。再読込しても 0 | MEMBER | PASS |
| #4375 | 他組織の利用者が、実在チャンネルと不在 ID を `?channel=` で直打ち: どちらも `GET /chat/channels/{id}` は **404 `CHAT_001`**。応答本文・画面の全文（「指定されたチャンネルにアクセスできません」）・その間に叩いた API の一覧がすべて一致。チャンネル名は出ない | 他組織 | PASS |
| #4375 | de で、チャット一覧・チャンネル画面・作成ダイアログに日本語の行が 0 件（利用者が付けたチャンネル名を除く） | MEMBER | PASS |
| #4375 | チームのチャット画面で DM を開き、Zimmer に招待（履歴を共有）→ 作成直後に `GET /chat/channels/{id}` で取り直し（200）、他人（MEMBER）の投稿にごみ箱が出て、削除できる | ADMIN（作成者） | PASS |

## 操作した要素・開始 URL・最終画面

| テスト | 開始 URL | 操作要素 | 最終画面 |
|---|---|---|---|
| マイルストーン | `/teams/{t2}/projects/{id}` | 「マイルストーン」見出しの「作成」→ タイトル欄（プレースホルダ「マイルストーン名」）・期限 →「追加」。カードの鉛筆 → タイトル → 「更新」。「編集」→ タイトル →「更新」 | タイトルが `MS renamed …`、見出しが `Plan edited …`（再読込後も） |
| マイルストーン de | `/teams/{t2}/projects` → 詳細 | 「Erstellen」「Abbrechen」「Bearbeiten」 | 日本語行 0 |
| 時間割（ADMIN） | `/teams/{t2}/projects` → ハンバーガー `scope-sidebar-toggle` →「スケジュール」→「時間割」 | タブ `timetable-tab-term`、`term-add-button`、`term-name-input`／`term-start-input`／`term-end-input`／`term-year-input`、`term-dialog-submit`、`timetable-create-button`、学期の選択、`timetable-tab-list`、`timetable-delete-{id}`、`term-delete-{id}`、確認ダイアログの承認・キャンセル | 時間割の無い学期が一覧から消え、「学期を削除しました」 |
| 時間割（MEMBER／他組織） | `/teams/{t2}/timetable` 直打ち | なし（見えない・出ないことの確認） | MEMBER は読み取りのみ。他組織は中身なし |
| チャット編集・削除 | `/chat?channel={id}` | 入力欄 `team-chat-input`、⋯メニュー →「編集」、`chat-message-edit-body`／`chat-message-edit-save`、`chat-message-delete` | 削除済み表示（`chat-message-deleted`） |
| 未読 | `/chat` | `chat-channel-{id}`（バッジ `chat-channel-unread`） | バッジなし |
| 存在隠蔽 | `/chat?channel={実在}` と `/chat?channel=987654321` | なし | 同一の画面・応答（404 `CHAT_001`） |
| Zimmer | `/teams/{t2}/chat` | `chat-channel-{dm}`、ヘッダーの招待ボタン（ユーザー追加アイコン）、候補の選択、「共有する」、「Zimmerを作成」、`chat-message-delete` | 他人の投稿が削除済み表示 |

### ロール3視点（まとめ）

- **権限あり（ADMIN／作成者）**: 学期の追加・削除、時間割の作成・DRAFT 削除、マイルストーン・プロジェクトの編集、チャンネル OWNER としての他人の投稿の削除ができる。
- **権限なし（MEMBER）**: 時間割・学期・プロジェクトは読める。追加・削除・編集の導線は出ない。チャットでは自分の投稿だけ編集・削除でき、他人の投稿には削除が出ない。
- **他テナント**: プロジェクト詳細・時間割・チャンネルのどれも URL 直打ちで中身が出ない。チャンネルは実在と不在で同じ 404 `CHAT_001`、同じ画面。

## 新しく見つかった欠陥（観測事実）

1. **DM の開始が API の宛先違いで 405**
   - 観測: `POST /api/v1/chat/channels/dm`（body `{userId}`）は 405（`COMMON_004` このエンドポイントでは指定されたHTTPメソッドは使用できません）。BE の会話開始は `POST /api/v1/chat/channels/conversations`（`{userIds}`）で、こちらは 200/201。
   - FE: `frontend/app/composables/chat/useChatChannels.ts:106`（`getOrCreateDm`）が `/channels/dm` を呼び、`frontend/app/components/chat/ChatCreateDialog.vue:110` が 1 人を選んだときにこれを使う。BE は `backend/src/main/java/com/mannschaft/app/chat/controller/ChatChannelController.java:204` の `/conversations`。
   - 画面からは、確かめていない（API で 405 を観測。前提の DM は `/conversations` で作った）。
2. **汎用の `/chat` 画面では、DM から Zimmer に招待するときのメンバー候補が空**
   - 観測: ADMIN が `/chat?channel={dm}` で招待ボタンを押すと、候補欄が「招待できるメンバーがいません」（第三の MEMBER を検索しても出ない）。チームのチャット画面（`/teams/{slug}/chat`）では候補が出て、招待できた。
   - 手がかり: `frontend/app/components/chat/ChatInviteToZimmerDialog.vue` の `loadMembers` は `teamId` か `organizationId` が渡されたときだけ候補を取る。`frontend/app/pages/chat/index.vue:322` の `<ChatMessagePanel :channel="tab.channel" />` は両方とも渡さない。
3. **他組織の利用者に `GET /teams/{t2}/me/permissions` が 200 で返る**（軽微・観測のみ）: 他組織の利用者がチームの時間割ページを直打ちすると、この API は 200 を返した。時間割と学期の API（`/timetables` は 403）は閉じており、画面に中身は出なかった。応答の中身（権限が空か）は確かめていない。
4. **（前回の欠陥の再確認）直ったもの**: 前回の欠陥 1（学期追加の欄エラー）、2（409 のとき翻訳キー丸出し）、3（表見出しの翻訳キー）、4（チームの時間割で学期を選べない）は、いずれも再現しなくなった。前回の欠陥 5（送信中の Enter）は今回は確かめていない。

## 後始末と残骸

- spec が作った組織・チーム・チャンネル・DM・Zimmer・時間割の無い学期は、globalTeardown で 204（または既に無い 404）まで削除した。DB は直接触っていない（使い捨て利用者の有効化 `users.status` の更新は、既存の実機 spec と同じ前提作成）。
- **時間割が付いた学期は、仕様（`TIMETABLE_022`）上、あとから削除できず残る**。今回は、①チーム画面の「時間割を作る→DRAFT 削除」の確認（実行ごとに 1 件）と、②既存の ④（実行ごとに 1 件）で生じる。親チームは削除済み。ID を確認できたもの: 36・46・52。spec の途中失敗で記録前に終わった実行の分を含め、合計は約 4〜6 件。
- 登録した使い捨て利用者（`e2e-n1p1-*@test.mannschaft.local`）は、前回と同じく残る。アカウント言語を de にした 2 名は、テストの最後に ja へ戻した。
- 検証用に作ったもの: WSL の clone と成果物（`~/vf-src`、`/var/tmp/vf-app.jar`、`verification-run` の証跡ディレクトリ）は撤去した。`~/verify-1507/app.jar` は稼働中の BE の実体として残している。

## 未実施と理由

| 対象 | 理由 |
|---|---|
| 汎用 `/chat` 画面から DM を開始する操作そのもの | 欠陥 1 のとおり API が 405 のため、前提の DM は API（`/conversations`）で作った |
| ④ の「同組織 MEMBER が学期を API 直叩きで削除した場合」 | API は前提・後始末にだけ使う方針（前回と同じ） |
| #4358 の組織側画面での年度 27・日付逆転 | 同じ共通部品 `TimetableTermManager` を使う。今回はチームで確かめ、組織は ③ の年度空のみ |
| 他の言語（en/zh/ko/es）の日本語残存 | 今回は de のみ（依頼範囲） |
