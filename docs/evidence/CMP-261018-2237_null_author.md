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
| AC7 | 実認可・別テナント境界 | 実機担当が検査。コンポーネントのAPIスタブを実認可の証拠として扱わない | 検査継続中 |

未修正 main `0bbbad0ed7` に同じ spec を置き、13件実走・8失敗/5成功（3.51秒、exit 1）。通常名・初回403の対照試験が成功し、AC1/空文字fallbackの実 assertion 失敗を確認してから実装した。

製品差分だけを同期し、同じ spec・同じ happy-dom 設定で13件すべて成功（3.08秒、exit 0）。対象3コンポーネント・spec・型定義の ESLint は exit 0（`lint.log`）。6言語のJSON parseと空でない `bulletin.unknownAuthor` キー、`git diff --check` も成功。

Windows の標準 Nuxt 設定による試験は sparse 展開外の i18n ファイルを読む ENOENT で起動失敗したため、red/green に算入しない。WSL の専用 ext4 コピーで Node 22.23.2・既存依存を使用し、Nuxt を起動しない既存 Vue/Vitest の専用 happy-dom 設定で実行した。専用設定は Nuxt のテンプレート用 sanitizeHtml auto-import を製品関数への import で補う。ハーネス不備を補正する前の通常作者失敗も、正式 red から除外した。

確定実行ログは本陣の ignored `.claude/evidence/cmp2237-20261020/unit-confirmed-red.log` / `unit-confirmed-green.log`、専用設定は作業 worktree の ignored `frontend/test-results/vitest-cmp2237.config.mjs`。Windows では本陣 `frontend/node_modules` への junction を再利用した。撤去時は junction リンクだけを外し、本陣の依存実体は削除しない。

## 検証状況と残存事項

- 実機の修正前確認では、通常の掲示板一覧から thread163 をクリックして詳細へ遷移し、題名headingの不在 assertion と `null.charAt` を確認した。修正後・ロール/テナント横断・探索・CI（型検査と関連既存単体を含む）は継続中。未実行を合格とは扱わない。
- 初回取得403では本文・管理操作を描画しないことを検査した。既存実装が取得失敗後も LoadingBounce を表示し続ける UI 課題は今回の NULL 作者修正の対象外。
- 状態は実装中。親の検分・実機・探索の合格後に完了判定する。
