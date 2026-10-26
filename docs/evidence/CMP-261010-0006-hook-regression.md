# CMP-261010-0006 Git hook共有書込の回帰証拠

対象commit: `3a79816eda`（修正前）

## 再現

```text
node --test --test-name-pattern "linked worktree" scripts/harness-doctor.test.mjs
```

linked worktreeのgitDirとcommonDirが異なるfixtureで、共有 `pre-commit` と同じ内容のhookに実行bitが無い状態を作る。chmod処理はspyに置き換え、共有ファイル自体は変更しない。

修正前は、隔離worktreeから共有hookへのchmodが1回呼ばれた。

```text
AssertionError [ERR_ASSERTION]: linked worktreeから共有hookをchmodしない
1 !== 0
```

このテストは `inspectHooks` が共有Git metadataへの変更を行う前に `gitDir` と `gitCommonDir` を比較し、隔離worktreeでは `outside-worktree` として書込を止めることを確認する。
