---
name: inventory-update
description: 「棚卸更新」として、最新の feature-inventory.yaml と task-list.md を正本に、CMP・GitHub Issue/PR情報を同期してβ棚卸しボードのデータを再生成し、指定されたローカルHTMLへ反映する。
metadata:
  short-description: β棚卸しボードを正本とCMP・Issueから更新
---

# 棚卸更新

β棚卸しボードを更新するときに使う。HTMLを手編集せず、正本と既存の生成スクリプトから表示データを作る。

## 正本と生成物

- 機能の正本: `docs/inventory/feature-inventory.yaml`
- CMPの正本: `docs/task-list.md`
- GitHub Issue/PRの取得済みスナップショット: `docs/prototypes/beta-inventory-board-github.json`
- GitHub同期状態: `docs/prototypes/beta-inventory-board-github-status.json`
- 補助JSON: `docs/prototypes/beta-inventory-board-*.json`
- 生成データ: `docs/prototypes/beta-inventory-board-data.js`
- 表示本体: `docs/prototypes/beta-inventory-board.html`（通常は変更しない）

台帳やCMPの内容を推測で書き換えない。実装状態・blockers・CMP・Issueの変更が必要な場合は、先にそれぞれの正本を更新する。

## 実行手順

1. リポジトリルートとGit状態を確認する。未コミットの利用者変更がある場合、上書きせず維持する。
2. 必要なら `git fetch origin main` を実行し、今回使う正本のコミットを明示する。未マージのローカル変更を勝手にmainへ取り込まない。
3. 現在の作業ツリーにある最新の `docs/task-list.md` を使い、次を実行する。

   ```powershell
   python docs/prototypes/sync-beta-inventory-github.py --repo kenta-0420/mannschaft
   ```

   この同期は `task-list.md` のCMP行に明記された `#100` 以上の番号だけを対象にする。成功時だけスナップショットを置き換え、認証・通信・APIエラー時は既存スナップショットを保持して停止する。Issue/PRのタイトル、状態、URL、更新日時、開いているPRのCI状態を同期する。

4. GitHub同期が成功したことを確認してから、正本から生成する。

   ```powershell
   python docs/prototypes/generate-beta-inventory-board-data.py
   node docs/prototypes/validate-beta-inventory-board.mjs
   ```

   CMP件数、feature件数、能力展開、重複、正本との整合が検証できない場合は公開用ファイルを更新しない。

5. ユーザーがデスクトップ版を指定した場合、既存のHTMLとデータJSを日時付き `.bak` に退避してから、次をコピーする。既定の対象は `C:\Users\kenta\Desktop\beta-inventory-board.html` と同名の `-data.js`。

   ```powershell
   Copy-Item docs/prototypes/beta-inventory-board.html C:\Users\kenta\Desktop\beta-inventory-board.html -Force
   Copy-Item docs/prototypes/beta-inventory-board-data.js C:\Users\kenta\Desktop\beta-inventory-board-data.js -Force
   ```

   `file://`で開くHTMLは隣接する `beta-inventory-board-data.js` を読むため、HTMLだけを更新して終わりにしない。HTMLの意匠に利用者固有の変更がある場合は、HTMLを機械的に置き換えず、データJSだけを更新する。

6. 最終報告では、正本コミット、GitHub同期日時と参照件数、生成・検証結果、総レコード/CORE/非CORE/blockers/CMP件数、コピー先、バックアップ先、同期できなかった場合の理由を日本語で示す。

## 境界

- Artifactや外部公開先の更新はこのスキルの責務に含めない。公開を依頼された場合は、利用可能な公開ツールと既存URLを確認してから別途扱う。
- GitHub同期は読み取り専用だが、取得済みスナップショットと生成データはローカルで更新する。認証情報をファイルへ保存しない。
- `docs/inventory/feature-inventory.yaml`、`docs/task-list.md`、HTMLの手入力部分を、棚卸し更新のついでに自動編集しない。
