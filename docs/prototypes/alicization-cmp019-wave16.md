# CMP-019 Wave 16 実機・アリシゼーション（2026-09-28）

- 対象: PR #3499 / Wave 16。無断キャンセル確定と緊急確認通知。
- 状況: **作業中／実機未合格／アリシゼーション未実施**。PR はドラフト。
- CI検証済み head: `9284e770bf24bf3391ea391c29ab27e4822b4109`

## CI

最新CIは [run 36362722711](https://github.com/kenta-0420/mannschaft/actions/runs/36362722711)。backend の shard 0〜5 はすべて pass。OpenAPI Drift Check、npm ci（Ubuntu/glibc・Alpine/musl）も pass した。main push 限定の Docker ビルド検証と週次フル履歴スキャンは対象外で skip。

| JUnit suite | tests | skipped | failures | errors |
| --- | ---: | ---: | ---: | ---: |
| `RecruitmentNoShowConfirmPenaltyIT` | 2 | 0 | 0 | 0 |
| `RecruitmentScopeContractIT$LiftPenalty` | 7 | 0 | 0 | 0 |

これらのCI結果は実機検証・3住民の自由探索の合格を示すものではない。

## 実機 E2E

初回試行は `SYSTEM_ADMIN` の役割 fixture が合わず失敗したため、fixture を修正した。

再実機試験（5024）では GLOBAL ペナルティが1件、CN URGENT 通知が本人宛てに1件生成された。一方、pending API は `UserEntity` の LazyInitializationException（ユーザーID 23、no session）で HTTP 500 となった。実機シナリオは未合格。生成データの cleanup は成功した。本人の検索条件を維持した利用者情報の一括取得と、取得後に永続化コンテキストを切り離す回帰試験を追加し、修正版の検証を準備中。

## 3住民の自由探索

3住民での自由探索は未実施。予定枠と結果を混同しないよう、現時点の記録は次のとおり。

| 予定枠 | 状況 | 観測 |
| --- | --- | --- |
| 住民 1 | 未実施 | 未確認 |
| 住民 2 | 未実施 | 未確認 |
| 住民 3 | 未実施 | 未確認 |

## AC 対応表

AC番号と各観測結果との対応は未確認。受け入れ条件を照合した後に追記する。

| AC | 条件 | CI | 実機 E2E | 3住民探索 |
| --- | --- | --- | --- | --- |
| 未確認 | 未確認 | 上記 JUnit suite を参照 | 未合格 | 未実施 |

## 後片付けと残る確認

今回の実機試験で生成したデータの cleanup は成功した。pending API の LazyInitializationException を解消した実機再試験、3住民の自由探索、およびAC対応の照合はこれから行う。結果を確認するまでは Wave 16 を合格として扱わない。
