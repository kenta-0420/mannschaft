# CMP-261116-1539 採用照合

旧ブランチの固有3コミットの変更は PR [#4222](https://github.com/kenta-0420/mannschaft/pull/4222) に採用済みである。対象BE差分の独立検分証拠は未確認のため、元の受け入れ条件を完了扱いにしない。

## 比較対象

旧基点 `9fbbd04d04393ea8e143ebf365400d774e8ee919` 以降の固有コミット:

- `df8942f9b67f2bf567dcc38b8e4cf2f6c0b507db`：4サービスの0件時単体テスト。
- `bbe26f7657c18a4cc620970f1b742d20b44b9a9e`：空スコープの実SQL本数IT。
- `0d286d4e604ea23585206649e8d6ea025dadf4b4`：4サービスの0件時プレビュー省略と仕様。

`git diff --name-only 9fbbd04d04393ea8e143ebf365400d774e8ee919 0d286d4e604ea23585206649e8d6ea025dadf4b4` で得た以下の10ファイルについて、旧最終コミットと #4222 最終head `31d77e9f3e14f19d4d002e8750ff675cc06511be`、merge `21690dd56e98884a0a17e98d40a089187f23e9f7`、照合時main `a7d7c8fa8f0e015a976ea4b8d74cc4fc84e8f727` の各ペアで `git diff --quiet <旧最終> <比較先> -- <対象10ファイル>` は exit 0。対象ファイルのバイト内容は同一である。旧コミットの祖先関係ではなく対象内容で照合した。

| 対象 | 旧最終時のblob SHA | 照合内容 |
|---|---|---|
| `backend/src/main/java/com/mannschaft/app/matching/service/MatchingAdminQueryService.java` | `c50adb7dc4c8df5b839a384073f029e4e81ec5de` | teamのpending countが0ならpreview省略 |
| `backend/src/main/java/com/mannschaft/app/payment/service/PaymentAdminQueryService.java` | `27934047a636e1019eea0b4431cc42b66e05daee` | ORGの未収countが0ならpreview省略 |
| `backend/src/main/java/com/mannschaft/app/reservation/service/ReservationAdminQueryService.java` | `b6c030804cb7a576e956b6622c2b0ffbfe8a10ef` | teamのpending countが0ならpreview省略 |
| `backend/src/main/java/com/mannschaft/app/shift/service/ShiftRequestAdminQueryService.java` | `f7ce5da3c1b697e7b53ef7b26703efb57f2d31b8` | 変更・交代の合計が0なら両preview省略 |
| `backend/src/test/java/com/mannschaft/app/matching/service/MatchingAdminQueryServiceTest.java` | `9039dc31781d59021e89ae75644059e03a21ae85` | 0件時preview・名前取得なし、正数時の既存試験 |
| `backend/src/test/java/com/mannschaft/app/payment/service/PaymentAdminQueryServiceTest.java` | `5d37fcd7738b69413d224ab51e58388f04f82534` | 0件時preview・名前取得なし、正数・ORG scopeの既存試験 |
| `backend/src/test/java/com/mannschaft/app/reservation/service/ReservationAdminQueryServiceTest.java` | `6aecb8ee8a8f74c8f7b85daa728ae8cfda5541b0` | 0件時preview・名前取得なし、正数時の既存試験 |
| `backend/src/test/java/com/mannschaft/app/shift/service/ShiftRequestAdminQueryServiceTest.java` | `71c189fcd6ad72093464257eedddf7064c9ffef3` | 両0件の省略と片方のみ正数の両方向の取得 |
| `backend/src/test/java/com/mannschaft/app/dashboard/AdminActionRequiredEmptyQueryCountIT.java` | `8e38f9cdc1feff45a07190700be72f097a684ff0` | 実DBの空scopeで予約1・シフト2・マッチング1・未収請求1本を検証 |
| `docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md` | `d6e0b7d09f4f1c2ace63c2db6b10f695d7512edc` | 0件時省略とSQL本数の契約 |

## CI・未確認事項

#4222 は2026-11-20 20:51:01 UTCにマージされた。最終headのGitHubチェックは成功28、条件付きskip 2で、[Backend CI](https://github.com/kenta-0420/mannschaft/actions/runs/44784986375) のshard 0〜7および集約が成功した。これは集約結果であり、個別JUnit XMLの実行件数は照合していない。

対象BE差分と採用headを特定した独立検分の正式記録は未確認。GitHubのレビュー欄が0件であることは、他の手段での検分がなかった証明ではない。再開時は、検分担当、対象・基準SHA、4サービス・試験・仕様の範囲、所見と差し戻し解消を記録で確認する。旧headの記録を使う場合は採用headとの差と関連する呼出し・試験の前提も照合する。記録を確認できなければ、採用済み差分を事後に独立検分し、実施時期と判定を記録する。事後検分をマージ前の通過として扱わない。

親 CMP-261029-1254 の1000ms目標、正式測定、認可横断の実機および探索は、この採用照合によって達成扱いにしない。

## 追記（2026-11-21）

個別CI原本の照合と事後の製品独立検分は[事後検分](CMP-261116-1539-review.md)を参照。上記の独立検分証拠が未確認との記載は採用照合時点の記録である。実機・探索は別の完了ゲートである。
