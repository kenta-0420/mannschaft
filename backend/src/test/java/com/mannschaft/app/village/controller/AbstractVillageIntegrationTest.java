package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * F17.1 村機能 Controller 統合テスト共通基底クラス（Phase 1）。
 *
 * <p>{@link AbstractMySqlIntegrationTest} を継承し、Village 系統合テスト全体で
 * 必要となる {@link AccessControlService} のモック化を集約する。</p>
 *
 * <h3>なぜ AccessControlService をモック化するか</h3>
 * <ol>
 *   <li>本タスク（Village CRUD・検索）は SYSTEM_ADMIN 判定でのみ
 *       {@link AccessControlService#isSystemAdmin(Long)} を呼ぶ。
 *       他のドメイン（roles テーブル等）にデータ投入してまで判定経路を通すのは
 *       テストコスト過大。</li>
 *   <li>Village ドメイン内でのみ振る舞いを差し替えれば十分。
 *       他ドメイン統合テストの ApplicationContext には影響しない（村固有 Abstract で集約）。</li>
 * </ol>
 */
public abstract class AbstractVillageIntegrationTest extends AbstractMySqlIntegrationTest {

    /** SYSTEM_ADMIN 判定のみテスト側で制御するためモック化する。 */
    @MockitoBean
    protected AccessControlService accessControlService;
}
