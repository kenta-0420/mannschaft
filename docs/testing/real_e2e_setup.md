# 実機E2E（`frontend/tests/e2e/real/`）実行手順

実機E2E（`real` 系スペック）はモックなし・実BE(:8080)・実FE(:3000) を踏む。CI対象外で殿が手動実走する。

## 必須: Nuxt dev サーバの SSR ワーカー OOM 対策

**症状**: 実機E2E実走中に、原因不明の要素未検出（"要素が見つからない"）や、実際にはログイン済みのはずのページで
ログイン画面のような描画になる、など**紛らわしい形の失敗が大量発生**することがある。

**真因**: `npm run dev` の Nuxt dev サーバが SSR レンダリングに使うワーカープロセスが、
`Worker terminated due to reaching memory limit: JS heap out of memory` で落ちている。
ワーカーが落ちた状態でリクエストを受けると、期待したページの代わりに壊れた/空の応答が返り、
テストからは「要素が無い」「見当違いの画面に見える」ように観測される。テストのロジックやセレクタの
問題ではなく、**サーバ側のプロセスが死んでいる**ことが原因。

**対策**: dev サーバ起動時に Node のヒープ上限を引き上げる。

```bash
NODE_OPTIONS=--max-old-space-size=8192 npm run dev
```

これを与えたところ、OOMに起因して赤くなっていた FAV-001/FAV-002/FAV-010 と dashboard 系のテストが緑に転じた
（2026-08時点で実測）。実機E2Eを実走する際は**必ず**この環境変数を付けて dev サーバを起動すること。

## 失敗時の調査手順

テストが失敗したら、まずは**サーバが生きていたか**を疑う。安易にタイムアウト延長や待機処理の追加で
誤魔化さず、以下を確認すること:

1. `test-results/*/error-context.md` の **Page snapshot** を必ず見る。
   OOMでワーカーが落ちている場合、ここに Nuxt のエラー画面（Worker terminated / JS heap out of memory の
   スタック）がそのまま写り込むため、原因切り分けの一次情報になる。
2. dev サーバのログに `Worker terminated due to reaching memory limit` が出ていないか確認する。
3. 上記のいずれかが確認できたら、`NODE_OPTIONS=--max-old-space-size=8192` を付けてサーバを再起動し、
   再実行する（タイムアウト値そのものは変更しない）。
4. OOMではなく製品側の不具合が疑われる場合は、実機ブラウザで手動確認し、ネットワークタブでAPI応答を
   実測してから対処すること（憶測でテスト側を緩めない）。

## 参考: 待ち受け（`page.waitForResponse`）を書く際の注意

`page.waitForResponse` は**登録した後に発生した**イベントしか捉えない。目的のAPIが `page.goto` の
直後〜画面描画中に発火する場合、待ち受けの登録は **`page.goto` と同じ `Promise.all` に含めて goto より
前に登録**すること。見出しの可視化待ちなどを挟んでから `waitForResponse` を呼ぶと、その間にAPIが
既に応答を返し終えていて、リスナーが「過ぎ去った応答」を待ち続けタイムアウトする
（`frontend/tests/e2e/real/circulation-member.spec.ts` CIRC-001 で実際に発生した不具合）。

## 参考: URL識別子は slug 一本化

本プロジェクトはチーム等のURL識別子を **slug** に一本化済み。`/teams/1` のような数値IDのURLは
チームページとして解決せず、アプリの外枠（ヘッダ・ナビ）だけが描画され見出しも本文も出ない。
テストで対象チームのURLを組み立てる際は、必ず実在する slug（例: `team-000092`）を使うこと。
