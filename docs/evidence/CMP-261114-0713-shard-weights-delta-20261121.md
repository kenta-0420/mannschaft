# CMP-261114-0713 成功nightlyの同一source未掲載65クラス追補

適用元は 6ae8ffa5eb44fb94e10f066cc27946416cd9de29。guard run44835888502/job136642095708はeligible3346・未掲載104（上限100）、full/normalの予測比1.0000だった。閾値・分類・既allocator・上限・workflow・製品7修復pathは変更しない。

[成功nightly 44426618464 / attempt1](https://github.com/kenta-0420/mannschaft/actions/runs/44426618464/attempts/1)、source 5461674e4fda1d9c23f4691a72cc6e9217b077cc の全8fresh runtimeを、現正規generatorのsafe_zip/build_weights（script blob 8957131761a77be85fb75d50c4211ed251b0d4b0）で検証した。ZIP digest/size、安全member、全N/同run/head/attempt、step全exit0、normal:test終端、class開始終了対応とSUCCESS・重複0を要求し、3302クラスの正規fullregen候補を未適用出力した。workflow sourceでは8shard・test --rerun・migration除外無しも確認した。

## 限定範囲と既存値の保全

fullregen候補の新規68キーのうち、各Javaテストblobが適用元と完全同一の65だけをrootが個別採択した。changed3は除外する。全表の既存値3205変更・13削除は採用しない。現表3247キーの値と既存13追補を全て保持し、65追加後3312キー、既キー上書き/削除0。冒頭の3234クラス等のheaderは旧nightly snapshotの来歴として変更せず、追補blockで新来歴と実キー総数を明示する。

これはroot個別delta採択の既例（[20261115](CMP-261114-0713-shard-weights-delta-20261115.md)、[20261120](CMP-261114-0713-shard-weights-delta-20261120.md)）と同じ限定方式である。fullregen CLIのsuccessful nightly条件は緩めず、Backend CIのoverall failureをnightlyと偽称せず、中央値を実測として登録しない。

値は通常:testのclass-suite elapsedMs/1000。outerがある場合は子を二重加算せず、nested-onlyは既generatorと同じ独立最外suiteを合算する。JUnit XML timeや現headのfresh測定とは呼ばない。

## root採択65（秒）

| FQCN | shard | elapsedMs | 秒 | 同一テストblob | artifact ID |
|---|---:|---:|---:|---|---:|
| com.mannschaft.app.bulletin.BulletinTournamentCategoryListContractIT | 7 | 3028 | 3.028 | 9da96a860194656d30e725cf78cfe2085e1a63f5 | 14491708061 |
| com.mannschaft.app.common.i18n.ErrorMessageI18nGuardTest | 2 | 1253 | 1.253 | 6c7005ad43ef9c764e6c204dc6c2710bee150c4e | 14490139587 |
| com.mannschaft.app.common.i18n.ErrorMessageResolveI18nTest | 3 | 1114 | 1.114 | dfe11f2cfd06024688f06e6815d2e97b2ca39f9b | 14489511644 |
| com.mannschaft.app.common.migration.MigrationPrimaryKeyConventionScanningTest | 1 | 25 | 0.025 | 3932a57fb33b98058a9fa56a89ffc5b027fbf1ae | 14492351356 |
| com.mannschaft.app.config.LocalTimeOpenApiContractTest | 6 | 6 | 0.006 | ba5c00bfda57fb3c598a2a63810b8851afc0a649 | 14489854974 |
| com.mannschaft.app.dashboard.DashboardActivityErrorLocaleTest | 4 | 82 | 0.082 | d5196deb215d728a36f77ee10b8b4a47c0a9487e | 14493585386 |
| com.mannschaft.app.effortrecord.EffortApiContractArtifactsTest | 0 | 2360 | 2.360 | db68d64a0e951380663df4af8986c7289cb952d8 | 14495229244 |
| com.mannschaft.app.effortrecord.EffortPresetSeedCanonicalTest | 5 | 60 | 0.060 | ef3927df53e1eb5d7db4ae24ef8e939b50b6d4be | 14488924910 |
| com.mannschaft.app.effortrecord.EffortRecordGdprExportIT | 2 | 116665 | 116.665 | 0c0c3d4e7ff650ac92f133d0807f525581499afd | 14490139587 |
| com.mannschaft.app.effortrecord.EffortRecordIdempotencyIT | 7 | 58219 | 58.219 | 6ef115104bc5fb8c2c3c34f840948ec461da2b4c | 14491708061 |
| com.mannschaft.app.effortrecord.EffortRecordInputValidationContractIT | 3 | 59782 | 59.782 | 0a6bb10a0ebe4edef367b8e31a7cdeac9ba3ece3 | 14489511644 |
| com.mannschaft.app.effortrecord.EffortRecordListCursorContractIT | 1 | 948 | 0.948 | e5a0872b28d7ec182a762819d6dc5eb8f6b74934 | 14492351356 |
| com.mannschaft.app.effortrecord.EffortRecordObservabilityIT | 6 | 85972 | 85.972 | b6fface7d8c274cece2a4fe640ecd94ca205933b | 14489854974 |
| com.mannschaft.app.effortrecord.EffortRecordOwnerApiContractIT | 4 | 58301 | 58.301 | efdb36f8ea451f75dbe146aef2a7cd6afcbbaef5 | 14493585386 |
| com.mannschaft.app.effortrecord.EffortRecordTemporalIT | 0 | 167133 | 167.133 | 0facb7f978e88589129dfd3870e353190b803c9e | 14495229244 |
| com.mannschaft.app.effortrecord.EffortStatsContractIT | 5 | 82906 | 82.906 | 9b7e550f3615cd9c1e2d2c194cd87cc008a723fe | 14488924910 |
| com.mannschaft.app.effortrecord.migration.FlywayEffortRecordsTablesMigrationTest | 2 | 125001 | 125.001 | 7c4c16f27c2931aba91a870c605e53e5cd03cd1c | 14490139587 |
| com.mannschaft.app.errorreport.ModuleEventOperationalAlertIT | 7 | 51841 | 51.841 | b3261b7ea44a6399dfcec5b9be80c3407f3604fb | 14491708061 |
| com.mannschaft.app.errorreport.ModuleEventOperationalAlertListenerTest | 3 | 313 | 0.313 | 172a91d037ba280229c070a9e29bcdb909497014 | 14489511644 |
| com.mannschaft.app.moduleevent.ModuleEventAdminApiIT | 1 | 58934 | 58.934 | f0452b1ac8a9a2c7799e02d53a8d57497ff0be27 | 14492351356 |
| com.mannschaft.app.moduleevent.ModuleEventExecutionAppIT | 6 | 46114 | 46.114 | d93a79b7cc90e62867244b9edd9c94e488af2664 | 14489854974 |
| com.mannschaft.app.moduleevent.ModuleEventExecutionBoundaryTest | 4 | 616 | 0.616 | 45fafa3a5eca8ceef3a42eb0d5693e8e0cb7fef3 | 14493585386 |
| com.mannschaft.app.moduleevent.ModuleEventExecutorWiringTest | 0 | 97 | 0.097 | 0addc3dedb62bf11e3c478cbbf9519a5f78e5098 | 14495229244 |
| com.mannschaft.app.moduleevent.ModuleEventImpactBindingIT | 5 | 77305 | 77.305 | 311e1eff326f9f52ea6fcd35c2d4450f702a586d | 14488924910 |
| com.mannschaft.app.moduleevent.ModuleEventJpaWiringTest | 2 | 918 | 0.918 | 1906bd1061d7933445b6513960ce136db6a63f70 | 14490139587 |
| com.mannschaft.app.moduleevent.ModuleEventListenerCatalogTest | 7 | 37 | 0.037 | 50911b623221e3f84c6d7923c1cae6059f38fcb8 | 14491708061 |
| com.mannschaft.app.moduleevent.ModuleEventLoadIT | 3 | 354875 | 354.875 | 43de4a9f6210e3c4533cdb51225b39b2cc5d703e | 14489511644 |
| com.mannschaft.app.moduleevent.ModuleEventNoticeCoreHooksIT | 1 | 62794 | 62.794 | e6e8d7e6a61b9d91533806c3eccdbc0d383b0bac | 14492351356 |
| com.mannschaft.app.moduleevent.ModuleEventOperationsIT | 6 | 50693 | 50.693 | c337545f4eb80af5c0f1a0e74e5563171e5239df | 14489854974 |
| com.mannschaft.app.moduleevent.ModuleEventPurgeReplayIT | 4 | 50916 | 50.916 | d54689320f506bdcec29eb7e8a782a7c64b3629b | 14493585386 |
| com.mannschaft.app.moduleevent.ModuleEventRegistryIT | 0 | 91483 | 91.483 | 33dd4edb2042c945b6f97166f424210228733c0a | 14495229244 |
| com.mannschaft.app.moduleevent.ModuleEventReplayBatchIT | 5 | 85290 | 85.290 | 30684cec6588969a31c194335d5dc500700bcb29 | 14488924910 |
| com.mannschaft.app.moduleevent.ModuleEventReplayBoundaryIT | 2 | 77274 | 77.274 | 520c0a7f303f2bfbfbf5c49331d1d1b12e3b566b | 14490139587 |
| com.mannschaft.app.moduleevent.ModuleEventReplayIT | 7 | 61692 | 61.692 | f9a64a71036f3ef833bedb20c2d7a5539361a2e4 | 14491708061 |
| com.mannschaft.app.moduleevent.ModuleEventReplaySideEffectIT | 3 | 62513 | 62.513 | a877579d861d89a7eecb78f49620a3633bbc04f7 | 14489511644 |
| com.mannschaft.app.moduleevent.ModuleEventResumeAuditPrivacyIT | 1 | 63185 | 63.185 | 3047514138c766e810143b6221e4e5cc94f6e811 | 14492351356 |
| com.mannschaft.app.moduleevent.ModuleEventResumeAuditRetentionBatchIT | 6 | 46112 | 46.112 | 3f9de2941a7823499d6d47bbb5b22cc79d7fee75 | 14489854974 |
| com.mannschaft.app.moduleevent.ModuleEventResumeAuditRetentionBatchTest | 4 | 5 | 0.005 | 0d2c89290256131214d931012f24ca46b581a700 | 14493585386 |
| com.mannschaft.app.moduleevent.ModuleEventSchedulingIsolationTest | 0 | 126 | 0.126 | 8ce098804a079ecac961c807ef6cae81a1665671 | 14495229244 |
| com.mannschaft.app.moduleevent.ModuleEventSerializationIT | 5 | 72891 | 72.891 | c38172abeeae6ed4a01bf6367b0b37988115144a | 14488924910 |
| com.mannschaft.app.moduleevent.e2e.ModuleEventRealE2eBootstrapBoundaryTest | 2 | 10 | 0.010 | 20bf0833e2c85bffaa0f2a08e4a45a5ab7064059 | 14490139587 |
| com.mannschaft.app.moduleevent.notice.ModuleEventImpactEvaluatorTest | 7 | 16 | 0.016 | 8f3d991f8adc5d0b4d0ea4b056e1772198ff151b | 14491708061 |
| com.mannschaft.app.moduleevent.notice.ModuleEventImpactResolverRegistryTest | 3 | 39 | 0.039 | a5201ad3ab9a750fb50eaecad46588b39b8ca53b | 14489511644 |
| com.mannschaft.app.moduleevent.notice.ModuleEventNoticeLifecycleIT | 1 | 5151 | 5.151 | 047b70993d92fc8b5fd159bb0dbe6255ab487029 | 14492351356 |
| com.mannschaft.app.moduleevent.notice.ModuleEventNoticeLockOrderIT | 6 | 380 | 0.380 | c6fdfe686f438869262a79fab68e03d4c072b20e | 14489854974 |
| com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxApiIT | 4 | 51707 | 51.707 | 69ade46684883b87f9c8e93a4f9aac5c20e486f1 | 14493585386 |
| com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxIT | 0 | 4661 | 4.661 | d438b29ae7c11f003ac5a04e5c59b411034b5e01 | 14495229244 |
| com.mannschaft.app.moduleevent.notice.ModuleEventNoticePrimaryRoutingTest | 5 | 720 | 0.720 | 194ff52768792c0e5f04a5addd1a9741deac7844 | 14488924910 |
| com.mannschaft.app.moduleevent.notice.ModuleEventNoticeRecoveryPumpIT | 2 | 66086 | 66.086 | 733615f35b94bcc1e2c0992e5a74972c01ecadb9 | 14490139587 |
| com.mannschaft.app.moduleevent.notice.ModuleEventNoticeRelayApiIT | 7 | 62295 | 62.295 | 2659b42a466eb85ea8949acdf3ac9339825cfd14 | 14491708061 |
| com.mannschaft.app.notification.moduleevent.ModuleEventNoticeDispatchIT | 3 | 59898 | 59.898 | e828d484822a78fcc86be658b69fe645b363b8bf | 14489511644 |
| com.mannschaft.app.notification.moduleevent.ModuleEventNoticeFanoutChunkSinkIT | 1 | 69365 | 69.365 | 5a5789550275ef04fe6624099cd3a465be97c8c5 | 14492351356 |
| com.mannschaft.app.notification.moduleevent.ModuleEventNoticeJobTransitionsIT | 6 | 46874 | 46.874 | a3f3d1f6b835ff4174886b6b3fcd0c7b79b4429b | 14489854974 |
| com.mannschaft.app.notification.moduleevent.ModuleEventNoticeOutboxSourceIT | 4 | 49595 | 49.595 | 3c5784a51520c5e4fb23f2d02115e5a388e2df7d | 14493585386 |
| com.mannschaft.app.notification.moduleevent.ModuleEventNoticeRecipientSourceIT | 0 | 90398 | 90.398 | edb50b2950a4dc122080d62d60e986b71c659c87 | 14495229244 |
| com.mannschaft.app.notification.moduleevent.ModuleEventNoticeWorkerLifecycleIT | 5 | 177514 | 177.514 | f9444dd71c336a9b4b0382ceaf62b23c956d0e18 | 14488924910 |
| com.mannschaft.app.organization.teamgroup.OrgTeamListNumericIdIT | 2 | 63778 | 63.778 | 0a8161eda2e883e43ee21854c6584511a70cec44 | 14490139587 |
| com.mannschaft.app.shift.ShiftPublicationBudgetContextBoundaryIT | 3 | 68495 | 68.495 | 002f55f7e256e0d6f0caaccc0d764bf7dd4341e6 | 14489511644 |
| com.mannschaft.app.shift.ShiftPublicationHourlyRateContractIT | 1 | 308 | 0.308 | c674f8b2d661ab4296b4e1cc2bc77d6b05966970 | 14492351356 |
| com.mannschaft.app.shift.ShiftScheduleSummaryVisibilitySecurityContractIT | 6 | 57360 | 57.360 | 667a386e44798114c0eb76ab6438ba3d4b19c7bb | 14489854974 |
| com.mannschaft.app.shift.service.ShiftPublicationHourlyRateVerifierTest | 4 | 145 | 0.145 | 8297e39417dbd402bb2873b2f801ee0d2be15818 | 14493585386 |
| com.mannschaft.app.shiftbudget.ShiftBudgetPositiveRateContractIT | 0 | 2352 | 2.352 | 15bede36cf6b294d264a359c04ce04ab9b50b94a | 14495229244 |
| com.mannschaft.app.social.FriendContentForwardExistenceOracleContractIT | 5 | 163281 | 163.281 | c7db1fdfab3e95edf421300cd3b5eadd069eb38c | 14488924910 |
| com.mannschaft.app.support.test.sparse.SparseExcludedAssetsTest | 2 | 987 | 0.987 | 16b15a50a8b6155e029a1ec8ae76897390167bc0 | 14490139587 |
| com.mannschaft.app.timeline.TimelinePostStatusVisibilityContractIT | 7 | 57731 | 57.731 | 08e3d1a9fc7f1ca6ca1333d384c10fcd704e137d | 14491708061 |

## 原本・未実行検証

8 ZIPはnightly-runtime-diag-shard-N-attempt-1、使用memberはclass-progress.log。全65の開始/終了原文行・値・source blob・対応ZIPとmemberのdigestは、版管理された[65件の実測manifest](CMP-261114-0713/shard-weights-delta-20261121.json)に保持する。JSONはrootが検分した原本のJSON値と原文イベントを維持して収録し、Gitによる改行正規化がある。私用絶対pathや認証情報を含まない。

未掲載104→39は同じeligible集合かつ65がその集合に含まれることを前提とする推計で、compiled exact guardのgreenではない。現在のcompiled inventoryを保有していないため、verifyShardCoverageの4通常+2Arch profile・一意割当・full/normal偏り再計測は次CIへ持ち越す。Arch18/37・Integrity2・3fixture cleanup・上限100/1.10の実装はいずれも変更0であり、新CI成功は未主張。

追補作成前のRO確認時点のmain d11ee9fddc0c5cf63afdd23bb71112e66dd71efa に対しても65 source blobは全て同一、65キーの既登録0だった。適用元との122差分pathには当PRの製品7/CI等の差分を含む。root採択後は通常mergeによるmain追従を行い、最終HEAD/tree/parents/main SHAと手動差分を統合packetへ対応付ける。新CIでのguard実測は別に必要であり、旧nightly実測を現headのfresh成功やwhole-app同値とは呼ばない。
