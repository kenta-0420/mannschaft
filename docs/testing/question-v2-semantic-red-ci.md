# 質問v2 hosted限定RED（実行待ち）

状態: workflow製造、実行/回収/分類pending。追加5caseのsemantic REDは未取得。製品v2 patchは未適用。

専用branch `feature/question-v2-semantic-red-ci` のpushだけで起動し、PRを開かない。runnerはTEST_ONLY commit `49fb75180addfcab0a63b842f14bfeb7e3801a53` へliteral checkoutする。これは旧HEAD `3cb143f6880d2174d773c36133bf7d22ad6d1a33` に承認済みsix-path試練/文書だけを加えたsource。workflowと本運用記録の次commit/event SHAとは別なので両SHAを証跡へ記録する。

Git whole source treeは `b2a9cb219d8693995b62efc7e4d8139e06c6b898`、backend/src/main treeは旧HEADの `dfc4638b0c954721d8130ff0b753021aed461acd` のまま。既承認Windows archiveのraw whole-source SHA256 `02cd993e307f392420a9d39d715b99b623d751383d5436275f9c9d935a7493f1` とraw main SHA256 `6e76cccfaf45c06473d2bf7634139bc6a3372dff6ca50343fb13a27639df5d42` はCRLFを含む別digest。LF Git blob/sparse作業木/Ubuntu checkoutとraw archive digestを混同しない。

既存ownerと同じCatalog/ApprovedRegistry/Persistenceの3class selectorsを使う。追加caseはCatalogの新版24問と旧新版読取、Registryの全draft拒否、Persistenceの旧途中session保持と新版交互表示/逆極性採点の5つ。5caseともDisplayName/明示semantic labelなし。method identifierと実assertion差異で確認する。

期待する初期REDは、CatalogとPersistenceのquestionnaireVersion期待 `draft-20261010-v2` に対して実際 `draft-20261003-v1`、Registryで旧draft拒否通過後にv2正式登録が例外を発生しない差異。これはsourceからの予想であり実REDではない。実非zero Gradle exitと追加caseのrecognized assertion failure/executed/skipped=0を確認し、PersistenceではPOST201/no-storeを通過したことも確認する。

失敗jobは同じ実exitのまま残す。artifactは正確な3JUnit XMLと `question-v2-gradle-exit.txt` だけ、3日保存。raw logsをuploadしない。compile/setup/fixture/Docker/Future/HTTP201失敗、timeout、XML/exit欠損、既存caseだけの失敗は質問v2 semantic REDにしない。Persistenceのskip/欠損をUT REDと混ぜない。限定REDだけarchUnitTest/archUnitFreezeStoreIntegrityTestを除外し、通常CIには適用しない。

回収後は既存 `question-wording-test-owner-20261010-02/question-wording-owner.py` のinert `junit_results`/`summarize` とMETHOD_ALLOWLISTを再利用し、実exitと5caseのsemantic差異をrootが照合する。既存owner/receiverのWSL process/source proofをhosted結果から捏造しない。新validator/frameworkは作らない。actual RED採用まで製品設定/実装を適用せず、GREEN・Security filter認可・実機E2E・アリシゼーションの完了を主張しない。
