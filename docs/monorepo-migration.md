# モノレポ移行手順書

> **このドキュメントは移行手順書です。移行は完了済みのため、記載のSTEPは実行不要です。**
> 開発環境の起動方法は `.claude/commands/` 配下のスキルを参照してください。

## 前提条件
- 現状: `D:/mannshaft/`（バックエンド）, `D:/mannschaft-frontend/`（フロントエンド）の2リポジトリ
- GitHub: `kenta-0420/Mannschaft`（バック）, `kenta-0420/mannschaft-frontend`（フロント）
- 目標: Gitの歴史を保ったままモノレポ化

## 完成形
```
D:/mannschaft/         ← 新ルート（新GitHubリポジトリ: kenta-0420/mannschaft）
  backend/             ← 現 D:/mannshaft/ の中身
  frontend/            ← 現 D:/mannschaft-frontend/ の中身
  README.md            ← ルートに新規作成
  docker-compose.yml   ← backend/ から移動
  .github/             ← ルートで管理
```

---

## STEP 0: 事前準備

```bash
# git filter-repo のインストール確認
pip install git-filter-repo
# または
winget install git-filter-repo

# バックアップ（念のため）
cp -r D:/mannshaft D:/mannshaft_backup
cp -r D:/mannschaft-frontend D:/mannschaft-frontend_backup
```

---

## STEP 1: バックエンドの歴史を backend/ プレフィックスに書き換え

```bash
cd D:/mannshaft

# filter-repo で全ファイルを backend/ サブディレクトリに移動
git filter-repo --to-subdirectory-filter backend

# 確認
git log --oneline -3
git ls-files | head -5   # backend/src/... になっていること
```

---

## STEP 2: フロントエンドの歴史を frontend/ プレフィックスに書き換え

```bash
cd D:/mannschaft-frontend

# filter-repo で全ファイルを frontend/ サブディレクトリに移動
git filter-repo --to-subdirectory-filter frontend

# 確認
git log --oneline -3
git ls-files | head -5   # frontend/src/... になっていること
```

---

## STEP 3: 新ルートリポジトリを作成してマージ

```bash
# 新リポジトリ作成
mkdir D:/mannschaft
cd D:/mannschaft
git init
git commit --allow-empty -m "chore: initial monorepo root"

# バックエンドをマージ
git remote add backend D:/mannshaft
git fetch backend
git merge --allow-unrelated-histories backend/main -m "chore: merge backend history into backend/"
git remote remove backend

# フロントエンドをマージ
git remote add frontend D:/mannschaft-frontend
git fetch frontend
git merge --allow-unrelated-histories frontend/main -m "chore: merge frontend history into frontend/"
git remote remove frontend

# 確認
git log --oneline -10
ls   # backend/ と frontend/ が見えること
```

---

## STEP 4: docker-compose.yml をルートに移動・修正

```bash
mv D:/mannschaft/backend/docker-compose.yml D:/mannschaft/docker-compose.yml
```

`docker-compose.yml` 内のパスを確認・修正：
- `./Dockerfile` → `./backend/Dockerfile`
- ボリュームの相対パスがあれば修正

---

## STEP 5: Dockerfile パス確認

`backend/Dockerfile` と `frontend/Dockerfile` は相対パスなのでそのまま動くはず。
ただし `docker-compose.yml` の `context:` と `dockerfile:` を確認。

---

## STEP 6: 各設定ファイルのパス修正

### backend/gradle.properties, backend/settings.gradle.kts
→ 特に修正不要（相対パス）

### frontend/nuxt.config.ts
→ 特に修正不要

### frontend/package.json の scripts
→ 修正不要

---

## STEP 7: Claude Code の設定移行

```bash
# .claude/ ディレクトリをルートに移動
mv D:/mannschaft/backend/.claude D:/mannschaft/.claude

# スキルファイル内のパスを修正
# 対象: .claude/commands/ 以下の陣立て系ファイル
# 変更箇所:
#   /mnt/d/mannshaft/  →  /mnt/d/mannschaft/backend/
#   D:/mannshaft/      →  D:/mannschaft/backend/
#   D:/mannschaft-frontend/  →  D:/mannschaft/frontend/
```

修正が必要なスキルファイル:
- `.claude/commands/陣立て.md`
- `.claude/commands/陣立て1.md`
- `.claude/commands/陣立て2.md`
- `.claude/commands/陣触れ.md`
- `.claude/commands/伝令.md`

---

## STEP 8: CLAUDE.md があれば確認

```bash
cat D:/mannschaft/backend/CLAUDE.md   # あれば内容確認
# ルートに移動or更新
```

---

## STEP 9: GitHubに新リポジトリ作成 & push

```bash
# GitHub で kenta-0420/mannschaft を新規作成（空で）

cd D:/mannschaft
git remote add origin https://github.com/kenta-0420/mannschaft.git
git push -u origin main
```

---

## STEP 10: 陣立て2スキルの修正内容

以下のパスを更新：

| 変更前 | 変更後 |
|--------|--------|
| `/mnt/d/mannshaft/docker-compose.yml` | `/mnt/d/mannschaft/docker-compose.yml` |
| `D:/mannshaft && ./gradlew` | `D:/mannschaft/backend && ./gradlew` |
| `D:/mannshaft/build/libs/app-0.0.1-SNAPSHOT.jar` | `D:/mannschaft/backend/build/libs/app-0.0.1-SNAPSHOT.jar` |
| `logs/bootRun.log` | `D:/mannschaft/backend/logs/bootRun.log` |

---

## STEP 11: 動作確認

```bash
# Docker起動
cd D:/mannschaft
docker compose up -d

# バックビルド
cd D:/mannschaft/backend
./gradlew build -x test

# フロント起動確認
cd D:/mannschaft/frontend
npm run dev
```

---

## 注意事項
- STEP 1, 2 の `git filter-repo` は破壊的操作。バックアップ必須
- フロントのGitHub Actions（あれば）は `frontend/` プレフィックスで動作するよう修正が必要
- 旧リポジトリ（`D:/mannshaft`, `D:/mannschaft-frontend`）は確認後に削除
- `node_modules/` は `.gitignore` に入っているはずだが確認すること
