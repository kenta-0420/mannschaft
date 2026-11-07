# CMP-261023-1648 — Unicode公式fixtureのcheckout改行修正

- 関連Issue: [#4066](https://github.com/kenta-0420/mannschaft/issues/4066)
- 調査・実装指示: [コメント6366912327](https://github.com/kenta-0420/mannschaft/issues/4066#issuecomment-6366912327)、[コメント6367104830](https://github.com/kenta-0420/mannschaft/issues/4066#issuecomment-6367104830)
- 検証基準コミット: `982aa70de68e9234ec38f7fd76da3584bd5595d7`
- 環境: Windows、Node.js `v24.15.0`、Git `2.54.0.windows.1`

## 原因

`test-fixtures/unicode/17.0.0/GraphemeBreakTest.txt` は公式配布データの生バイトSHA-256を検査している。
Gitに保存されたLF版は126,570 bytesで、既存契約のSHA-256
`e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec` と一致する。
ルートの属性指定がない状態で `core.autocrlf=true` によりcheckoutすると796改行がCRLFになり、127,366 bytes、SHA-256
`991ccad2ea69e6aa4adeddab0664e5e4c8a3c2383388a5183ab77941e9f06e26` へ変わる。
このためWindowsで `RanchGraphemeCompatibilityTest` の公式データ完全性検査が失敗する。

## 修正と適用範囲

ルート `.gitattributes` で公式fixtureの上記1ファイルだけに `text eol=lf` を指定する。
公式データの内容と既存のSHAアサーションは変更しない。他のファイルや利用者のGit設定には適用しない。
Gitの [`eol` 属性の仕様](https://git-scm.com/docs/gitattributes#_eol) に従い、checkout時の改行をLFに固定する。

`scripts/unicode-checkout.test.mjs` は、Git blobから取得した公式データと作業ブランチの実際の `.gitattributes` を一時Gitリポジトリへ置き、indexからファイルを再checkoutする。
期待SHAは公式データの既存契約値を使い、CRLFの作業コピーを期待値に流用しない。
無関係なテキストファイルは `core.autocrlf=true` のときCRLFのままであることも確認する。
Git設定は一時リポジトリまたはコマンドの範囲だけで指定し、一時ディレクトリは所有する絶対パス範囲を確認して削除する。
新テストは既存 `harness-tests` の実行リストへ追加した。

## Red / Green

コマンド: `node --test scripts/unicode-checkout.test.mjs`

| 状態 | core.autocrlf=true | false | input | 終了コード |
| --- | --- | --- | --- | --- |
| 属性追加前 | SHA不一致 `991ccad2…` / 期待 `e2d134d2…` | 成功 | 成功 | 1 |
| 属性追加後 | 成功 | 成功 | 成功 | 0 |

Greenでは親テストを含む4 testsが全成功、skip/cancelは0。`git diff --check` も成功。

## 全件未完走との区別

これは公式fixtureのcheckout変換による独立した失敗要因の修正であり、Spring contextやDB再利用による所要時間の改善とは別である。
この修正だけでBE全件テスト完走を確認したとは扱わない。重いGradle・Docker試験、画面を用いた実機E2E・アリシゼーションは未実施（変更はGit属性とNode回帰テストに限定）。
CIの完了確認・マージとCMP完了判定は、全件試験改善側の証拠と合わせて司令塔が行う。
