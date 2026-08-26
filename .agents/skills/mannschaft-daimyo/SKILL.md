---
name: mannschaft-daimyo
description: Route and execute Mannschaft's existing Daimyo workflows without duplicating their instructions. Use when the user invokes or mentions /軍議, /試練, /出陣, /検分, /実機, /凱旋, /物見, /巡回, /陣払い, /陣立て, /陣立て1, /陣立て2, /陣触れ, /撤収, /引継, /早馬, /伝令, /絵図, /統一, /手助け, or /手助けモーダル, or asks for the corresponding Japanese-named Mannschaft workflow.
---

# Mannschaft Daimyo

既存の Claude Code 用コマンドを正本として、そのまま Codex の作業手順へ適用する。手順本文をこのスキルへ複製しない。

## 実行手順

1. リポジトリルートを特定し、ルートの `CLAUDE.md` と `AGENTS.md` を全文読む。
2. ユーザーが指定したコマンド名から `.claude/commands/<コマンド名>.md` を解決し、必ず全文読む。
3. 同ファイルから直接参照される規約・設計書のうち、今回の作業に必要なものを読む。
4. Claude Code 固有のツール名、モデル名、frontmatter は Codex で利用可能な同等手段へ読み替える。それ以外の安全則、品質ゲート、承認条件、報告項目は維持する。
5. 読み込んだコマンドの手順を実行する。破壊的操作や外部状態の変更には、Codex の現在の権限・承認規則も適用する。

## コマンド解決

- `/名前`、`名前して`、`名前をお願い` などは `.claude/commands/名前.md` へ対応させる。
- 引数は既存コマンド文書中の `$ARGUMENTS` として解釈する。例: `/陣払い 14` は `陣払い.md` を14日指定で実行する。
- `deepseek:名前` が明示された場合だけ `.claude/commands/deepseek/名前.md` を使う。それ以外は通常版を使う。
- 対応ファイルが存在しない場合は、似た名前を推測実行せず、利用可能な `.claude/commands/*.md` を確認してユーザーへ短く尋ねる。

## 正本管理

- ワークフローの変更は `.claude/commands/*.md` 側だけに行う。このスキルへ本文を転記しない。
- 新しいコマンドが追加されても、一般的な解決規則で扱える限り本文の更新は不要。自動トリガーさせたい場合だけ frontmatter の description に名前を追加する。
