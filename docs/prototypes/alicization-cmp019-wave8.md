# CMP-019 Wave 8 実機・アリシゼーション（2026-09-26）

- runId: `CMP019-W8-ALIC-1790430732535`
- 対象: タイムラインフィードのカーソル、追加ロード、権限境界
- 環境: FE `http://localhost:3002`、BE `http://localhost:8081`、実 MySQL。BE health 200、FE 応答 200 を確認。
- 標的モード。3 住民が別アカウント・別 BrowserContext で操作。DB 直接操作なし。

## 決めた手順の実機 E2E

`frontend/tests/e2e/real/timeline/timeline-feed-cursor.spec.ts` を Chromium 実機プロジェクトで実行し、`1 passed (1.4m)`。PUBLIC 投稿の `limit=2` 初回・次ページ、途中挿入・削除、ピンの初回のみ表示と通常列からの分離、TEAM 投稿のちょうど 2 件の最終ページ、画面の 20→21 件自動ロード、投稿詳細への遷移、別ユーザー・匿名の閲覧拒否を確認した。カーソル付き追加リクエストは HTTP 200。作成した 27 投稿とチームは ID 指定 API で削除し、全 BrowserContext を閉じた。[21 件表示時の実画像](evidence/cmp019-wave8/real/team-feed-21.png)。

## 住民の配役と観測

| 住民 | 人格 × 攻め口 | アカウント・目的 | 実測 |
| --- | --- | --- | --- |
| 足軽 1 | 一般 × 素直 | `e2e-user`。自分の TEAM 投稿を一覧から詳細まで読む | 初回の「メンバーの権限を初期設定」モーダルを「あとで決める」で閉じ、3 件を表示。feed API 200、最新投稿の詳細 `/timeline/439` まで到達。 |
| 足軽 2 | スマホ片手操作 × 表示崩れ | `e2e-admin`。小画面のフィードで古い投稿まで読む | 390×844、360×800 でログイン・`users/me` 200、`/timeline` 初期カードを実画像確認。390px は 2026/7/1 の旧投稿 2 件と下端の読み込み表示を確認。ページ横幅は 390/390px、360/360px。 |
| 足軽 3 | 高齢・低リテラシー × 隙間狙い | `e2e-outsider`。他人の TEAM 投稿を URL で読めるか試す | ログイン・`users/me` 200。`GET /api/v1/teams/{slug}` と modules は 403。TEAM URL と投稿直 URL は Loading 表示中に投稿カード 0 件で、本文は読めなかった。 |

足軽 1 の独立 TEAM fixture は slug `w8-alic-cmp019-w8-alic-1790430`、投稿 ID `437, 438, 439`。初回モーダルは非同期表示され、表示中は投稿カードのクリックを遮る。閉じた後は 3 件を読めた。[初回モーダル](evidence/cmp019-wave8/owner/first-visit-modal.png)、[TEAM フィード](evidence/cmp019-wave8/owner/team-feed.png)、[投稿詳細](evidence/cmp019-wave8/owner/post-detail.png)。画像は実際に開いて表示状態を確認した。

足軽 2 の実画像: [390px 初期](evidence/cmp019-wave8/mobile/390-timeline-initial.png)、[360px 初期](evidence/cmp019-wave8/mobile/360-timeline-initial.png)、[390px スクロール後](evidence/cmp019-wave8/mobile/390-timeline-scrolled.png)。画像を開いて、カードの左右が収まり、目視でも横パンがないことを確認した。DOM の `documentElement.scrollWidth/clientWidth` は 390/390px と 360/360px。ただし 390px の寸法採取時は Loading 中だった。360px で可視ボタンの実測は `button[aria-label="メニューを開く"]` が 36×36px、通知ボタンが 40×40px、見出し左の戻るボタンが 40×40px と、いずれも 44px 未満だった。現段階では住民の観測であり、同条件の再撮影・CSS確認をしていないため不備確定とはしない。足軽 2 の追加ロード完了 API status と 360px の古い投稿到達は未計測。21 件追加ロードの機能自体は上記 E2E で通過している。

足軽 3 の実画像: [TEAM URL の Loading](evidence/cmp019-wave8/outsider/team-timeline.png)、[投稿直 URL の Loading](evidence/cmp019-wave8/outsider/post-direct.png)。両画像を開いて投稿本文がないことを確認した。住民の撮影時点では FE dev の document navigation が遅く、Loading 画面が権限拒否後の最終表示かは未確定。戻り導線も未確認。API の 403 と本文非表示は実測であり、E2E では outsider の TEAM feed 403/404、直 URL から投稿本文を読めないことまで通過している。

## 実証と後始末

初回モーダルによるカードのクリック遮断はスクリーンショットと Playwright の `p-dialog-mask intercepts pointer events` で再現した。新規 TEAM の初期設定 UI として意図された表示であり、テストではモーダルを閉じてからフィード操作を行う。カーソルと認可の決定的な条件は上記の実機 E2E で確認した。

共有 fixture の投稿 ID `437, 438, 439` は作成者 API で ID 指定削除し、各 HTTP 204。TEAM 削除も HTTP 204、削除後の TEAM 詳細は HTTP 404、cleanup error 0 件。各住民の BrowserContext は閉じた。検証用 FE 3002 は停止し、LISTEN なしを確認した。
