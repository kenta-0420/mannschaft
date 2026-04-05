# 個人開発の落とし穴：ポリレポで始めたら、気づいたら詰んでいた話

## はじめに

「バックエンドとフロントエンドは別リポジトリに分けたほうがスマートでしょ」

そう思って個人開発を始めた結果、数ヶ月後に気づいた。

**フロントエンドがドキュメントを一度も読めていなかった。**

これは笑い話ではなく、チームで開発している会社でも頻繁に起きるアーキテクチャの意思決定ミスだ。この記事では「ポリレポ」と「モノレポ」という2つの構成の違いを、個人開発者・初学者の視点で深掘りする。

---

## そもそもポリレポ・モノレポとは？

### ポリレポ（Polyrepo）

複数のリポジトリに分けて管理する構成。

```
GitHub
├── kenta/my-app-backend     ← Spring Boot
└── kenta/my-app-frontend    ← Nuxt.js
```

「バックとフロントは責務が違うから分けよう」という発想から生まれる。大手企業（Netflix、Amazonなど）が採用していることも多く、一見スマートに見える。

### モノレポ（Monorepo）

1つのリポジトリにまとめる構成。

```
GitHub
└── kenta/my-app
    ├── backend/     ← Spring Boot
    └── frontend/    ← Nuxt.js
```

「全部1つで管理しよう」という発想。Google・Meta・Microsoftなどが社内モノレポを採用していることで有名。

---

## 何が起きたか：実体験ベースの落とし穴

### 落とし穴①：ドキュメントがバックエンドにしかなかった

```
D:/my-app-backend/
  README.md                     ← ここにある
  BACKEND_CODING_CONVENTION.md  ← ここにある
  FRONTEND_CODING_CONVENTION.md ← なぜかここにある（！）
  docs/
    API設計.md                  ← ここにある
    DB設計.md                   ← ここにある

D:/my-app-frontend/
  README.md  ← 別の README（内容が古い）
  （docs/ がない）
```

フロントエンド側で作業しているとき、バックエンドのリポジトリのドキュメントは**視界に入らない**。
エディタはフロントのディレクトリを開いているし、GitHubもフロントのリポジトリを見ている。

結果として：
- **フロントエンド開発者（自分）がAPI設計書を見ずに実装していた**
- **コーディング規約が守られていなかった**
- **READMEが2つに分裂して、どちらが正しいか不明になった**

### 落とし穴②：APIの変更がフロントに伝わらない

バックエンドでレスポンスの形式を変えた。

```diff
- { "userName": "kenta" }
+ { "name": "kenta", "displayName": "けんた" }
```

このコミットはバックエンドのリポジトリに入る。
フロントエンドのリポジトリには何の変化も起きない。

Gitのログを見ても、フロントのリポジトリには**その変更が存在しない**。  
数週間後、なぜフロントが壊れているのかわからなくなる。

### 落とし穴③：AI（Claude Code）が片方しか見えない

最近はAIを使って開発支援をすることも多い。
Claude CodeやGitHub CopilotなどのAIツールは、**作業ディレクトリのコードを読んで回答する**。

ポリレポだと、バックエンドのディレクトリで作業しているとき、AIはフロントのコードを知らない。

```
AI「このエンドポイント、フロントからどう呼ばれているか確認しましょうか？」
自分「...フロントのリポジトリ開いてないんだよな」
```

これが積み重なると、**AIの提案が片方のコードだけを考慮したものになり、整合性が崩れる**。

### 落とし穴④：docker-composeがバックに置かれる

インフラの設定（docker-compose.yml、.env など）はどちらに置くべきか？

直感的にバックエンドと一緒に置きがちだが、フロントのDockerfileはフロントのリポジトリにある。
結果として：

```
D:/my-app-backend/
  docker-compose.yml   ← MySQL + Redis の設定
  Dockerfile           ← バックのDockerfile

D:/my-app-frontend/
  Dockerfile           ← フロントのDockerfile（docker-composeから参照されていない）
```

フロントをDocker化したいとき、docker-composeを修正するためにバックのリポジトリを開かないといけない。構造が直感に反している。

---

## なぜポリレポを選んでしまうのか

初学者がポリレポを選ぶ理由には、よくあるパターンがある。

### 理由1：「プロっぽく見える」

技術記事や企業のアーキテクチャ紹介を読むと「マイクロサービス」「独立したリポジトリ」という言葉が出てくる。それを参考にして真似してしまう。

しかし、大企業がポリレポを採用するのは**組織が大きく、チームが独立して動く必要があるから**。1人の個人開発にその理由は存在しない。

### 理由2：「バックとフロントは別物」という思い込み

技術スタックが違う（Java vs TypeScript）から、別リポジトリが自然に思える。

でも「技術スタックが違う」と「リポジトリを分ける」は別の話だ。モノレポの中に `backend/`、`frontend/` ディレクトリを作れば、技術スタックは完全に分離できる。

### 理由3：チュートリアルがそうなっていた

「Spring BootのGitHubリポジトリを作ろう」という入門記事に従ってバックを作り、「NuxtのGitHubリポジトリを作ろう」という入門記事に従ってフロントを作ると、自然とポリレポになる。

チュートリアルは「1つのことを教える」ために分かれているだけで、それが正解ではない。

---

## 対処方法：モノレポへの移行

### 歴史を捨てる移行（簡単・30分）

Gitのコミット履歴は諦めて、ファイルだけ移動する。

```bash
# 新ルートディレクトリを作成
mkdir my-app
cd my-app
git init

# バックエンドのファイルをコピー
cp -r ../my-app-backend backend

# フロントエンドのファイルをコピー
cp -r ../my-app-frontend frontend

# .git は含めない（新しくgit管理する）
rm -rf backend/.git frontend/.git

# 最初のコミット
git add .
git commit -m "chore: monorepo 初期構成"
```

### 歴史を保つ移行（`git filter-repo` を使う）

コミット履歴を保ちながら移行する本格的な方法。

#### 1. git filter-repo をインストール

```bash
pip install git-filter-repo
```

#### 2. バックエンドの歴史を `backend/` に書き換え

```bash
cd my-app-backend
git filter-repo --to-subdirectory-filter backend
```

これで全コミットのファイルパスが `src/...` から `backend/src/...` に書き換わる。

#### 3. フロントエンドの歴史を `frontend/` に書き換え

```bash
cd my-app-frontend
git filter-repo --to-subdirectory-filter frontend
```

#### 4. 新リポジトリにマージ

```bash
mkdir my-app
cd my-app
git init
git commit --allow-empty -m "chore: monorepo ルート"

# バックエンドをマージ
git remote add backend ../my-app-backend
git fetch backend
git merge --allow-unrelated-histories backend/main -m "chore: merge backend"
git remote remove backend

# フロントエンドをマージ
git remote add frontend ../my-app-frontend
git fetch frontend
git merge --allow-unrelated-histories frontend/main -m "chore: merge frontend"
git remote remove frontend
```

#### 5. 完成形の確認

```
my-app/
├── backend/
│   ├── src/
│   ├── build.gradle.kts
│   └── ...
└── frontend/
    ├── app/
    ├── nuxt.config.ts
    └── ...
```

---

## モノレポにすると何が変わるか

### ドキュメントが一元管理される

```
my-app/
├── README.md                    ← 1つだけ
├── docs/
│   ├── API設計.md
│   ├── DB設計.md
│   └── インフラ設計.md
├── BACKEND_CODING_CONVENTION.md
├── FRONTEND_CODING_CONVENTION.md
├── backend/
└── frontend/
```

フロントエンドのディレクトリを開いていても、エディタのサイドバーにドキュメントが見える。

### APIの変更とフロントの修正が同じコミットに入る

```
git log --oneline

a1b2c3d feat: ユーザー名フィールドをdisplayNameに変更
         - backend/src/.../UserResponse.java
         - frontend/app/pages/profile.vue
         ↑ 1コミットで両方変更された記録が残る
```

「なぜフロントが壊れているのか」を調べるとき、Gitの歴史を1箇所だけ見れば済む。

### docker-composeがルートに置ける

```
my-app/
├── docker-compose.yml   ← ここ1つで完結
├── backend/
│   └── Dockerfile
└── frontend/
    └── Dockerfile
```

```yaml
# docker-compose.yml
services:
  backend:
    build: ./backend    # ← 自然なパス
  frontend:
    build: ./frontend   # ← 自然なパス
  mysql:
    image: mysql:8.0
```

### AIが全体を把握できる

Claude CodeをモノレポのルートディレクトリでVS Codeから起動すれば、AIは `backend/` も `frontend/` も同時に読める。

```
AI「backend/src/UserController.java の /users/me のレスポンスに
   displayName が追加されましたね。
   frontend/app/pages/profile.vue の this.user.userName を
   this.user.displayName に修正しましょうか？」
```

これがポリレポだと実現しない。

---

## モノレポのデメリットも理解する

公平のために、モノレポのデメリットも挙げる。

### デメリット1：リポジトリが重くなる

バックとフロントの `node_modules`、`build/` が1つのディレクトリに集まる。
`.gitignore` を適切に設定しないと、不要なファイルがコミットに含まれる。

```gitignore
# .gitignore（ルートに置く）
backend/build/
backend/.gradle/
frontend/.nuxt/
frontend/node_modules/
frontend/.output/
```

### デメリット2：GitHub Actionsの設定が複雑になる

「バックエンドのコードが変わったときだけバックをデプロイ」という設定が必要になる。

```yaml
# .github/workflows/backend.yml
on:
  push:
    paths:
      - 'backend/**'   ← これを設定する
```

最初は面倒に感じるが、一度設定すれば動き続ける。

### デメリット3：チームが分かれたとき不便になる可能性

将来フロントエンドを別の開発者に任せるとき、リポジトリへのアクセス権を分けられない。

ただし個人開発では関係ない話がほとんど。

---

## 考察：いつポリレポを選ぶべきか

ポリレポが適しているケースを整理する。

| 条件 | ポリレポ | モノレポ |
|------|----------|----------|
| 開発者が1〜2人 | ❌ | ✅ |
| バックとフロントが密結合（REST APIで繋がっている） | ❌ | ✅ |
| 将来チームを分けて開発する予定がある | ✅ | △ |
| サービスが完全に独立している（マイクロサービス） | ✅ | ❌ |
| 異なるデプロイサイクル（フロントは毎日・バックは週1など） | △ | △ |

個人開発・スタートアップの初期フェーズなら、**ほぼ全てのケースでモノレポが有利**。

---

## まとめ

今回の教訓をまとめる。

1. **「プロっぽい構成」と「自分に合う構成」は別物**  
   大企業の構成を真似する必要はない。規模と組織に合った選択をする。

2. **ポリレポはコンテキストを分断する**  
   ドキュメント、コミット履歴、AIの参照範囲、docker-compose、全てが分断される。

3. **モノレポは「1つのディレクトリに複数の技術スタック」を許容する**  
   バックとフロントを同じリポジトリに入れることと、技術スタックを混在させることは別の話。

4. **移行は早ければ早いほど楽**  
   コミット数が増えるほど歴史の書き換えが重くなる。迷ったら今すぐ移行する。

5. **個人開発でポリレポを選ぶ理由はほぼない**  
   本当にポリレポが必要になったとき（チーム分割・マイクロサービス化）に分ければいい。最初はモノレポで始める。

---

## 参考

- [git-filter-repo 公式](https://github.com/newren/git-filter-repo)
- [Googleのモノレポ戦略（英語）](https://cacm.acm.org/magazines/2016/7/204032-why-google-stores-billions-of-lines-of-code-in-a-single-repository/fulltext)
- [Turborepo](https://turbo.build/repo) — JavaScriptモノレポのビルドツール（参考まで）

---

*この記事は実際の個人開発プロジェクト（Spring Boot + Nuxt.js）でポリレポからモノレポへ移行した経験をもとに書きました。同じ落とし穴にハマる人が少しでも減れば幸いです。*
