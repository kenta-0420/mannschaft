# テストカバレッジ80%達成計画

## 現状 (2026-03-26 測定)
- **全体カバレッジ**: 41.2% (目標80%)
- **テスト総数**: 2,211件 (全パス)
- **80%達成済み**: 4モジュール (membership 88.9%, template 89.1%, role 81.6%, todo 81.9%)

## フェーズ構成

### Phase 1: ニアミスモジュール (60-79%) — 12モジュール
最もギャップが小さく効率的。追加テスト数: 約55-70メソッド。

| モジュール | 現在 | ギャップ(命令数) | 推定追加テスト数 |
|-----------|------|-----------------|----------------|
| common | 75.7% | 457 | 2 |
| organization | 69.3% | 278 | 6-8 |
| notification | 66.5% | 489 | 3-4 |
| dashboard | 64.5% | 1,432 | 5 |
| social | 64.0% | 263 | 4-5 |
| reservation | 63.7% | 1,028 | 8-10 |
| queue | 63.3% | 1,055 | 3-4 |
| shift | 62.7% | 1,004 | 6-8 |
| search | 61.9% | 305 | 2-3 |
| safetycheck | 59.4% | 817 | 3-5 |
| auth | 57.8% | 2,337 | 6-8 |
| admin | 57.6% | 1,237 | 6-8 |

### Phase 2: 中間モジュール (45-59%) — 9モジュール

| モジュール | 現在 | ギャップ(命令数) |
|-----------|------|-----------------|
| performance | 54.7% | 1,430 |
| event | 54.4% | 1,899 |
| moderation | 53.9% | 1,821 |
| chat | 53.2% | 1,174 |
| timeline | 53.1% | 1,116 |
| bulletin | 52.8% | 1,062 |
| facility | 48.6% | 2,316 |
| seal | 45.8% | 838 |
| schedule | 44.0% | 6,421 |

### Phase 3: 低カバレッジ中規模 (30-44%) — 8モジュール

| モジュール | 現在 | ギャップ(命令数) |
|-----------|------|-----------------|
| cms | 43.2% | 1,841 |
| circulation | 40.9% | 1,339 |
| filesharing | 39.4% | 1,431 |
| family | 38.7% | 1,842 |
| parking | 38.2% | 4,690 |
| matching | 38.0% | 2,135 |
| line | 35.2% | 1,124 |
| workflow | 31.7% | 1,940 |

### Phase 4: 低カバレッジ大規模 (20-30%) — 14モジュール

| モジュール | 現在 | ギャップ(命令数) |
|-----------|------|-----------------|
| timetable | 29.7% | 2,537 |
| forms | 29.2% | 1,806 |
| activity | 28.6% | 2,209 |
| tournament | 27.3% | 5,964 |
| member | 26.4% | 1,837 |
| team | 25.8% | 645 |
| advertising | 25.0% | 434 |
| survey | 24.5% | 1,933 |
| proxyvote | 24.4% | 4,222 |
| ticket | 24.0% | 2,926 |
| chart | 23.6% | 2,775 |
| directmail | 23.7% | 1,608 |
| service | 23.8% | 3,322 |
| corkboard | 23.4% | 1,380 |

### Phase 5: 最低カバレッジ (0-22%) — 8モジュール

| モジュール | 現在 | ギャップ(命令数) |
|-----------|------|-----------------|
| payment | 22.4% | 3,598 |
| promotion | 21.7% | 1,986 |
| gallery | 21.9% | 1,242 |
| digest | 20.6% | 3,216 |
| resident | 18.2% | 2,114 |
| receipt | 17.7% | 3,612 |
| equipment | 16.1% | 2,351 |
| config | 3.3% | 583 |

## 実行方針

- 各フェーズで大名システム（並列エージェント）を使い、3モジュールずつ並列修正
- フェーズ完了時に `./gradlew test jacocoTestReport` で80%達成を確認
- 確認後にコミット&プッシュ
- 次フェーズへ進行

## テスト作成規約 (TEST_CONVENTION.md準拠)

- @ExtendWith(MockitoExtension.class), @DisplayName, @Nested
- メソッド名: 操作_条件_期待結果 (日本語OK)
- BDDMockito: given/willReturn, assertThat, assertThatThrownBy
- ReflectionTestUtils.setField(entity, "id", value) でBaseEntity ID設定
- BusinessException検証: .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("XXX"))
- production code変更禁止 — テストのみ追加

## 進捗記録

| フェーズ | 状態 | 完了日 | カバレッジ |
|---------|------|-------|-----------|
| Phase 1 | 未着手 | - | - |
| Phase 2 | 未着手 | - | - |
| Phase 3 | 未着手 | - | - |
| Phase 4 | 未着手 | - | - |
| Phase 5 | 未着手 | - | - |
