# L1-B 撤去記録

製品L1-Bは最終e67のCI/検分とPR4169通常mergeで完了した。別の後処理であるraw cleanupも、残14rootへのユーザーの明示承認「許可」を受け、freshな所有・絶対path・停止・対象参照・Git採用を再確認して完了した。**今回14root / 888,898,616bytesを撤去し、既削除1root / 6,349bytesと合わせ累計15root / 888,904,965bytesの不存在を確認。raw保持0。**

最新の安全なreceipt要約は本Markdownに記録する。[cleanup.json](cleanup.json)と[build/manifest.json](build/manifest.json)はmain `a02a3dc0db2bd6c58e650e549f4eda206d3904b5` の削除前snapshotをbytes不変で保持しており、そこにある14root保持・承認待ち・期限は過去時点の記録である。旧原本receipt SHA256は `a1be0ba503e1d569d30c0d52a04cf08a6ef3acaf542106f151575343fef940dc`。現在の撤去結果・承認/監査の要約は以下を参照する。最終原本receiptは73,771bytes、SHA256 `e1ec87a310f37e205deba0b56067aa064aae16030d638fae24efa8d849646cdb`、完了時刻 `2026-10-28T11:24:12.605725+00:00`。安全な証拠はmain `a02a3dc0db2bd6c58e650e549f4eda206d3904b5` の42 Git blob / 29 packaged hash一致を削除直前に再確認した。小さな証拠JSON/XML要約・画像はGitに残り、プロセスargv/map・秘密・全file一覧・巨大rawは転載していない。

## 今回の確定結果と限界

- Linux12root / 839,335,265bytes、Windows2root / 49,563,351bytesを撤去し、全14絶対pathの不存在を確認した。既削除1件は二重削除していない。
- 自動承認審査による旧拒否・承認待ち保持は以下と削除前JSON snapshotへ履歴として保存する。今回の明示承認とfresh再確認によって解消しており、旧再確認期限や再開条件は現在の保留条件ではない。
- verification-run --checkはexit1。原L1-B runtimeの自隊scratchは0だが、別L1-Aのrun-55fg9Vと未帰属稼働run-1NaQ9fは対象外として不干渉。全システムのcleanup成功とは扱わない。
- Windows監査は読取可能なcommand line、WSL監査は読取可能な同UID参照を対象とした。保護/一時的に読取不能な無関係process、既知systemd/sd-pam、hash確認済み409bytesのUID0 cleanup証明の限界は原本receiptに保存した。既知の本戦役producer停止はroot確認済み。
- 対象外のWT、共有cache・開発DB/server、他戦役、削除対象外に保存したreceiptは削除していない。global cleanup --applyは実行していない。製品完了JSON/AC/テスト・実機の対象SHA、親戦役・別件1554の未完了は変更しない。

## 最終receiptの安全な集計

| 対象 | 今回削除root数 | bytes | 全対象不存在 | 現在保持root数 |
|---|---:|---:|---|---:|
| Linux | 12 | 839,335,265 | true | 0 |
| Windows | 2 | 49,563,351 | true | 0 |
| 今回承認対象計 | 14 | 888,898,616 | true | 0 |
| 先行撤去分を含む累計 | 15 | 888,904,965 | true | 0 |

最終原本の `approvedOperationSummary` はdeletedRootCount=14、deletedBytes=888898616、allApprovedRootsAbsent=true、retainedRootCount=0、retainedBytes=0、remainingRawRoots=[]。削除前にmain採用42 Git blob / 29 packaged hashのallMatch=trueを確認し、fresh参照監査はLinux targetMatchCount=0/unresolvedPidCount=0、Windows targetMatchCount=0。承認範囲は旧保持14rootに限定し、既知producer停止・所有/絶対path・対象参照0を再確認してから撤去した。原本receiptは削除対象外へ保存した。監査の読取可能範囲と対象外資産の限界は前節の通り。

## 旧保持条件（削除前の履歴）

- owner: root / gradle `9ec2bb94-5ba6-494b-9d4a-dd4ed47f29f5`。用途は完了証拠をGit採用済みの重複raw。
- 理由: 自動承認審査が、大きな証拠の削除について対象に対する具体的ユーザー承認不足として拒否した。初回一括拒否後、所有/停止/remote保存を追加確認して小root1件のみ承認・撤去されたが、次の205,401,645bytes rootで再拒否となり、全destructive作業を停止した。追加削除や別経路での迂回はしない。
- 再確認期限: `2026-10-29T10:00:00+09:00`。期限は状態再確認であり、自動削除の約束ではない。
- 再開条件: 残14rootへの明示的ユーザー承認、freshな所有/絶対path/停止/対象参照0再確認、root releaseがすべてそろった後のみ。未回答を承認として扱わない。

| 今回撤去した旧保持root（採取時fullpath） | bytes |
|---|---:|
| `/home/kenta/verification-evidence/CMP-261027-1646-after-finalweights-1` | 205,401,645 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-freeze-openapi-1` | 7,305,798 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-independent-1` | 7,462 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-oldweights-1` | 205,420,499 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-remaining-1` | 5,475 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-1` | 1,058 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-2` | 555 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-3` | 310,200 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-4` | 212,672,762 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-finalweights-1` | 205,430,432 |
| `/home/kenta/verification-evidence/CMP261027-l1b-runtime-final` | 783 |
| `/home/kenta/verification-evidence/CMP261027-l1b-runtime-final2` | 2,778,596 |
| `C:\Claude\mannschaft\.claude\evidence\CMP-261027-1646-l1b-real-support` | 2,313,562 |
| `C:\Claude\mannschaft\.claude\evidence\CMP-261027-1646-l1b-support` | 47,249,789 |

14root各々のprovenanceと保持条件はJSONへ残した。rawの絶対pathはhash索引・所有確認の対象であり、stage証拠参照には本repo内の文書を使う。この旧保持は今回の明示承認後のcleanupで解消した。製品の完了実証や別件1554/親戦役の状態は読み替えない。

## 削除前時点の撤去済みと非対象（履歴）

- 削除済み: `/home/kenta/verification-evidence/CMP-261027-1646-after-arch-continue-1` / 6,349bytes。残14件には含めない。
- root確認でbaseline/weights/implの3WTと実装local/remote branchは撤去済み。completion/manager WTは当時の手続き中に保持した。
- 実機の専用runtime/DB/Valkey/volume等は既存の停止・撤去証明へ対応する。今回のraw保持と実行中runtimeを混同しない。
- verification-run --checkは自隊scratch0。他/未帰属2entriesは不干渉とし、全システム撤去完了を主張しない。
- 調整台帳はL1-B done/held[]、build/weights解放、親closed=false。L0-M measurementと他ownerの未解消依存には触れていない。
