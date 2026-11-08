# Dashboard viewer_role 境界変換の再検証

- 実機でTEAM/ORGの応答は `viewer_role`、FE Panelは `viewerRole` を参照していた。`useDashboardApi` が無変換で返すため、表示方針の対象ロールが未確定になっていた。
- `useDashboardApi.ts` のTEAM/ORG取得だけで、生成型の `viewer_role` を既存FE型の `viewerRole` へ変換。取得URL・任意のstatsPeriod・その他データ・外枠を保持する。欠損/nullはFE型のnullへ正規化する。
- `useDashboardApi.spec.ts` の2件は実snake_case bodyを返し、TEAMのMEMBERとORGのSUPPORTERへの変換、他項目の参照保持、外枠meta、取得URLと回数を確認する。
- 修正前: 同じspecと実TSmoduleで2実行・2失敗・skip0。TEAMはMEMBER、ORGはSUPPORTERを期待したviewerRoleが両方undefined（実assertion失敗）。修正後: 2実行・2成功・skip0、exit0。
- ローカル実行は `npx vitest run --config .cmp261025-dashboard-wire.config.ts`。一時設定は `vitest/config` のnode環境・対象1spec・1worker・`~` aliasだけで、Nuxtの生成/auto-import変換/共通setupは使わない。同居の `useMemberCardApi.spec.ts` と同様、正規環境の静的auto-import用module mockと、局所実行用global stubを併用し、実API module本体と同じassertionを実行した。設定ファイルは検証後に削除し追跡しない。
- この局所検証はNuxt統合・画面表示の合格証拠ではない。正規Frontend CIと実機の最終確認は親担当。既存FE/BE起動・既存ログイン・共有生成物は操作していない。
