# CMP-261018-2237 掲示板スレッドの NULL 投稿者

## 根因と変更範囲

SURVEY 由来のシステム生成スレッドは `authorId = null`・本文空文字で作成される。掲示板 API は作者不在時も `author` オブジェクトを返し、その `id`・`displayName`・`avatarUrl` は NULL になる。FE のスレッド作者型が `id`・`displayName` を非 NULL と宣言し、詳細テンプレートが `displayName.charAt(0)` を評価するため、SectionCard のスロット描画で題名・本文も欠落していた。

スレッド作者の型を API 契約へ合わせ、詳細・一覧・保管庫で表示名が NULL または空文字の場合は `bulletin.unknownAuthor`（6言語）を使う。詳細 Avatar は確定した表示名の先頭文字を表示する。返信型・API・DB・認可は変更せず、追加 API 呼び出しもない。仕様書の作者カラム説明は V5.002 の NULL 許容と V62.012 のクロスドメイン FK 撤廃へ整合した。

## 回帰テスト

対象は `frontend/app/components/bulletin/BulletinThreadDetail.spec.ts`。実 SectionCard・実 PrimeVue Avatar・製品 sanitizeHtml を描画し、Vue の errorHandler で描画エラーを収集する。API・通知・相対時刻などの境界だけをスタブする。

| AC | テスト | 修正前 | 修正後 |
|---|---|---|---|
| AC1 | 投稿者の全fieldがnullでも題名・本文・代替名・Avatarを描画する | `h2` の存在 assertion が expected true / actual false で失敗 | 成功 |
| AC2 | SURVEY由来の空本文・返信0件・添付0件でも題名を描画する | 題名欠落による空wrapperの `text()` エラー。独立したassertion失敗とは数えない | 成功 |
| AC3 | 表示名の空文字・1文字・通常名 | 空文字の代替名 assertion 失敗。1文字・通常名は成功 | 3件成功 |
| AC4 | threadId切替でnull投稿者から通常投稿者へ再取得・表示する | 最初の代替名 assertion 失敗 | 成功 |
| AC5 | 一覧・保管庫のnull/空文字/通常投稿者名 | 各null/空文字は代替名 assertion 失敗、通常名は成功 | 6件成功 |
| AC6 | 初回getThreadが403なら本文と管理操作を描画しない | 成功（既存契約の回帰検査） | 成功 |
| AC7 | 実認可・別テナント境界 | コンポーネントのAPIスタブを実認可の証拠として扱わない | ADMIN/MEMBER/SUPPORTERの閲覧、非所属の404、匿名の401・ログイン誘導を実機で確認 |

未修正 main `0bbbad0ed7` に同じ spec を置き、13件実走・8失敗/5成功（3.51秒、exit 1）。通常名・初回403の対照試験が成功し、AC1/空文字fallbackの実 assertion 失敗を確認してから実装した。

製品差分だけを同期し、同じ spec・同じ happy-dom 設定で13件すべて成功（3.08秒、exit 0）。対象3コンポーネント・spec・型定義の ESLint は exit 0（`lint.log`）。6言語のJSON parseと空でない `bulletin.unknownAuthor` キー、`git diff --check` も成功。

Windows の標準 Nuxt 設定による試験は sparse 展開外の i18n ファイルを読む ENOENT で起動失敗したため、red/green に算入しない。WSL の専用 ext4 コピーで Node 22.23.2・既存依存を使用し、Nuxt を起動しない既存 Vue/Vitest の専用 happy-dom 設定で実行した。専用設定は Nuxt のテンプレート用 sanitizeHtml auto-import を製品関数への import で補う。ハーネス不備を補正する前の通常作者失敗も、正式 red から除外した。

確定実行ログは本陣の ignored `.claude/evidence/cmp2237-20261020/unit-confirmed-red.log` / `unit-confirmed-green.log` / `unit-typefix-green.log`。実際の専用設定 `vitest-cmp2237.confirmed.config.mjs` と再現コマンド `unit-reproduction-command.txt` は同証跡ディレクトリへSHA一致を確認して保全した。Windows では本陣 `frontend/node_modules` への junction を再利用した。撤去時は junction リンクだけを外し、本陣の依存実体は削除しない。

## 最終検分・CI

- [実装PR #4004](https://github.com/kenta-0420/mannschaft/pull/4004) は最終head `b52e2fd3a591705e2a70a1ce61c8d407f72576bf` で検分指摘0件、全checkの失敗・未完了0件を照合後、2026-10-20T08:47:51Zにsquash mergeした（merge `608be304340d6a40695235e2da5f4ed99374365d`）。完了JSONにはmerge SHAではなく実装PR head SHAを記録する。
- 初回 CI は、spec の一覧/保管庫コンポーネントを組にした `describe.each` から `mount` へ渡す型推論が膨張し、TS2590（union type too complex）で型検査に失敗した。製品 NULL 作者の回帰とは区別する。Vue の共通 `Component` 型を明示して修正後、同じ13件が成功（3.40秒、exit 0）、対象5ファイルlintも成功。初回Unitは型検査で停止し、成功には算入しない。
- 最終headの[Frontend CI](https://github.com/kenta-0420/mannschaft/actions/runs/39393101658) は型検査・lint成功、Unitは652ファイル・6380テストすべて成功。[E2E](https://github.com/kenta-0420/mannschaft/actions/runs/39393101287)、[Lighthouse](https://github.com/kenta-0420/mannschaft/actions/runs/39393101386)、[Backend CI](https://github.com/kenta-0420/mannschaft/actions/runs/39393101915)、lockfile・Harness・API Drift・OpenAPI・gitleaks・Task gate・cleanupも同headで成功した。Backendの実テストは変更条件によりskipされ、実行成功とは扱わない。

## 実機と認可境界

本陣の ignored `.claude/evidence/cmp2237-20261020/` に実測JSON・ログ・画像を保全し、殿が修正前後・拒否・住民探索画像と観測データを照合した。

- 修正前は通常一覧からthread163をクリックし、題名heading不在のassertionと `null.charAt` を再現した（`browser-confirmed-red-report.json` / `browser-confirmed-red-diagnostics.json` / `thread-detail-red.png`）。診断の成功は製品greenを意味しない。
- 修正後はADMIN/MEMBER/SUPPORTERが一覧→詳細とthreadId直URLの両方でAPIの題名、実際の日本語fallback、Avatar先頭文字を確認し、page/render errorは0件だった（`positive-three-role-green.json`、`browser-confirmed-green-report.json`、`thread-detail-green.png`）。実SURVEYの本文は空文字で、非空本文の保持はAC1のunitが担保する。
- 非所属ユーザーはAPIとブラウザの対象応答が404、`load-error-state` 表示、題名・一覧非表示を確認した（`outsider-role-result.json`、`outsider-confirmed-green-report.json`、`outsider-direct.png`）。
- 匿名は初期cookie0、API401、詳細URLから `/login` への誘導、題名・本文非表示、pageErrors空、context閉鎖を確認した（`anonymous-result.json`）。
- `final-runtime-report.json` が実機結果の集約、`product-source-match.json` が製品10ファイルの実行ソースとのハッシュ一致証拠。最終head追従はdocsのみで、製品ソースは実機・探索時から変更していない。共有DBへの直接DMLは行っていない。

## 独立した住民探索と限界

実機の後、別アカウントの3住民が独立に探索し、全員が題名・投稿者fallbackの確認を含む目的を達成してcontextを閉鎖した。証跡は同ディレクトリの `resident-admin/report.md`・`resident-member/report.md`・`resident-supporter/observations.md`、各住民の `observations.json`・画像。今回差分に起因する確定blockingは0件と判定した。

- ADMIN（一般・素直）は描画エラー0件。自動既読の201のみを観測した。
- MEMBER（高齢・低リテラシー・連打）は目的達成、エラー0件。3クリック座標の `/nav` 遷移観測はtargetを採取できず未確定で、本件の確定欠陥には数えない。
- SUPPORTER（390×844、スマホ片手・素直）は再試行後に目的達成。初回の `ERR_NETWORK_CHANGED` と動的import失敗は環境観測で、初回画像が上書きされたため初回状態の画像による再照合には限界がある。小さい既存タブの観測も今回差分の確定欠陥ではない。

## 残存事項

- 初回取得403では本文・管理操作を描画しないことを検査した。既存実装が取得失敗後も LoadingBounce を表示し続ける UI 課題は今回の NULL 作者修正の対象外。
- 検分・実機・探索と最終head CIの合格をもって完了とする。返信型・API・DB・認可処理の変更や追加クエリはない。
