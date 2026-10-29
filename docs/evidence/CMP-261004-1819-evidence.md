# CMP-261004-1819 試練・実装証拠

対象scopeは`app`。実装PRは[#4193](https://github.com/kenta-0420/mannschaft/pull/4193)。状態は実装中。以下の旧証拠は履歴として保持する。最新状況は末尾の2026-10-29追補を参照。最新main追従headのCI・実機差分smoke・最終検分待ちのため完了ではない。

## 原red（変更せず保存）

source head `1acfd1f2dc5cd43ff61111eda370c9f9e6f0825a`、[CI run 40589948384](https://github.com/kenta-0420/mannschaft/actions/runs/40589948384)、attempt 1、shard4 job `122491463835` の対象2XMLを限定採取した。artifact ID `12778100142`、ZIP SHA256 `ef3c682655e6fc843a7455ccb79603f5bd5f2e2790e2c224cff16b51226629a8`。全量ログ・認証情報・実機fixtureの値は公開しない。

| 対象 | tests | failures | pass | errors | skips | XML SHA256 |
|---|---:|---:|---:|---:|---:|---|
| `ShiftScheduleCreateTeamExistenceContractIT` | 20 | 6 | 14 | 0 | 0 | `5d3235435409d0489c733c6bf1c0aae11176217932290664068ebdca9385e370` |
| `ShiftScheduleCreateTeamExistenceFacadeTest` | 6 | 5 | 1 | 0 | 0 | `f3e905f934a7edf154b6871c380de3fbf805729caff6ceba91807a5a70aa3a39` |

26ケースの11 failは、製品原因10件とテスト契約の誤期待1件に分かれる。ITの未発行/0/-1/Long.MAX_VALUE/論理削除の5件では、期待403・増分0に対して実際に201・DB増分1を観測した。UTの5件では親照会の欠落、書込呼出し、実行順序の実アサーションが失敗した。起動失敗やcompile失敗をredに数えていない。

残る1件は文字列`null`に対する期待400 COMMON_001と、既存応答404 COMMON_005の相違だった。`GlobalExceptionHandler#handleTypeMismatch`/`isUnresolvedScopeSlug`の既存契約に合わせ、AC-07とテスト期待だけを訂正する。欠落/空queryは400、文字列nullは404として別表示し、合計26ケースを保つ。handlerは変更しない。

Facade XMLのsuite名はJUnitのDisplayName（原文の文字化けを含む）でFQCNと異なる。修復せず原本を保持したうえで、固定XML basename、全testcase.classnameのFQCN完全一致、件数、producer/source/digestからrootが独立に同定した。

## 実装後CI（旧head b200の証拠）

source head `b200eb62290e48d01eaab8a5c215784a64b3442a`、[CI run 40601837099](https://github.com/kenta-0420/mannschaft/actions/runs/40601837099)、producer attempt 1、shard4 job `122531761872` の公式artifact `12782406903`（ZIP 17,578,339 bytes、SHA256 `451e39f7558915a6be36bd95e41738f12fcda54cff9f1634a9bf5786c00630a8`）から対象2XMLを限定採取した。

| 対象 | tests | failures | pass | errors | skips | XML SHA256 |
|---|---:|---:|---:|---:|---:|---|
| `ShiftScheduleCreateTeamExistenceContractIT` | 20 | 0 | 20 | 0 | 0 | `1e37a9d38463d7dd85a5ff5c56ef0f17d2f66b6147229b6d75160edeafac9769` |
| `ShiftScheduleCreateTeamExistenceFacadeTest` | 6 | 0 | 6 | 0 | 0 | `9e42a22b584253c843c722066379c240e4a8d3f2b29becbce64737c470181afd` |

rootが固定XML basenameの一意一致、全testcase.classnameのFQCN完全一致、20/6件とfail/errors/skip各0を照合した。suite DisplayNameは書き換えず原本を保持する。この26件合格は旧head b200に対する証拠であり、後続headや全体CIの合格へ読み替えない。

同runの全体CIは失敗。shard3は5,682件中成功5,679・失敗1・skip2で、唯一の失敗は既存`ShiftScheduleSlotFacadeContractIT#不在IDは不在コード`の`S3_CREATE`だった。旧テストはSYSTEM_ADMINの不在teamId作成201を別課題として固定しており、実際は今回の是正どおり403 COMMON_002になった。S3_CREATEだけ期待403へ更新し、TEAM一覧の200空とその他ID起点の既存契約を保持する。製品実装と上記26ケースは変更しない。期待値修正後の再CIは未実施であり、全体greenとは扱わない。

## ローカル試行の制約

同じ原headを正規canonical runner・turnstile・fresh cacheで実行したローカル試行は、compileJava/compileTestJavaと既存archUnit関連taskを経て`test FAILED`、exit1に到達した。ただし対象suite metadata照合で採取を停止し、ローカル26件の実数とactual redを独立認証できていない。対象XMLはscratchの清掃前に保全できず、ローカルをred/greenの証拠へ算入しない。上記CI原本が実redの根拠である。

rootの最終確認ではcanonical cleanup=removed、元source/headは不変・clean、own group/scratch参照/新規containerの残存0。ローカル失敗を成功扱いせず、実装許可後にのみ製品編集を開始した。

## 実装とAC対応

| AC | 実装・試験への対応 | 現在の判定 |
|---|---|---|
| 01/02 | Facadeで既存認可後に親存在照会。Optional.emptyならCOMMON_002。実フィルタMySQL ITの不在5ケースでDB増分も検証 | 原red実証済み、b200対象IT green |
| 03/04 | SYSTEM_ADMIN所属/非所属・通常ADMIN/DEPUTY_ADMIN/user_rolesのみADMINの201と返却/保存teamIdを同ITで確認。既存FacadeTest正常2件は新依存stubを各ケースへ追加 | b200対象IT green |
| 05/06 | MEMBER/SUPPORTER/他teamADMIN/非所属、実在/不在、匿名401とDB0 | 原CIはpass、b200対象IT green |
| 07 | 欠落/空の400と文字列null404を分離。既存handler不変 | 誤期待1件訂正、b200対象IT green |
| 08/09 | 専用Facade UTはempty/例外のwrite0、拒否lookup0、許可lookup1→write1の順序を検証 | 製品red5件実証済み、b200対象UT green |
| 10 | 不正日付SHIFT_010、feature gate優先の実フィルタIT。既存Service検証は変更なし | 原CI対象ケースpass、b200対象IT green |
| 11 | Facade非TX、公開TeamService窓口のみ。既存Authz/TX番人の変更なし | b200全体CI失敗。旧契約期待値修正後の再CI待ち |
| 12/13 | 実認証の実機ロール横断/匿名直API、不在teamId POST、後続の独立3persona目的探索とtriage/正常閉鎖 | 未実施 |

未削除親の存在確認だけを追加し、ACTIVE限定、新エラー、migration、別ドメインRepository参照は追加しない。同時親削除のTX保証は別課題1821へ分離する。原台帳ACと実装中状態を保持する。修正後の重いローカルbuild/testは資源割当のため未実施で、通常CIの確認はrootが担当する。

## 2026-10-29 追補（完了保留）

旧head `304db81dc552ba522698320e0b664290175e9dd2` は公式CI run [40643278817](https://github.com/kenta-0420/mannschaft/actions/runs/40643278817) と後続Task-List PR Gateをrootが照合し、実効check22 SUCCESS・1 SKIP・pending/fail0、選択XML445 passを確認。旧b200の失敗・原red・ローカル未認証は履歴として残す。

AC-12は旧実機source `3e3c8bd7a21ed5200a4d4198dcb5dcb016654bbd` のUI7ロール・API23ケースを根拠とする。304とのprotected code同値証明SHA256 `6e995048c82f3cf599ad8a0b68d840258fe763a5bb81070aa30482a4d5c0cfec` をrootが採用した。最新head全体の実機合格とは主張しない。AC-13は[独立3住民選択観測](CMP-261004-1819/resident-observations.json)の実施・triage・正常閉鎖をroot採用。目的は管理者達成、一般メンバー/部外者は部分達成、画像評価なし。未実証観測は累積B0へtrusted=false/observed-unverifiedで登録し、coverageや確定bugへ昇格しない。

最新main `f59ea6d054a4a61a05e3084e6b2b1083ccef7673` を隔離WTで競合なくmergeしたheadは `6c1faf5cae5903fbfa3e0bc30884f25a2d015546`。304からbackend/src/mainへの追加はTransitionalBridge annotation1件のみで、1819 protected製品コードは不変。mainのbuild-logic移設、GlobalNavItemのinteraction prefetch、calendar変更を取り込んだため、新headのCIと通常導線smokeを別途必要とする。旧CIを新head greenへ流用しない。

[閉鎖・復元選択要約](CMP-261004-1819/closure-summary.json)のとおり2026-10-29 02:08:19 UTCに全起動サーバ・専用DB停止、4port不在、試験データ復元を確認。old lease延長なし。追加clickの自動承認レビュー拒否は未実行・再送なしとして保持する。台帳は実装中のまま、新head CI・実機差分smoke・最終検分・merge後の完了証拠確定まで保留する。

検証限界: B0通常validator全体はCMP正本608行と生成campaign602件の既存不一致で失敗。今回行の追加削除なし・生成データのoverlay以外不変を確認した。overlay限定自己検証は空insights・複数head/runの受理と不正10件拒否を含め成功。全体validator合格・ボードUI検証・coverage合格とは扱わない。
