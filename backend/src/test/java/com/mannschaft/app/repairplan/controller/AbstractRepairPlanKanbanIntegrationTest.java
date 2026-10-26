package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.repairplan.module.RepairPlanModuleGuard;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * F08.8 Phase 4 相見積もりカンバン Controller / Service 統合テスト共通基底クラス。
 *
 * <p>{@link AbstractMySqlIntegrationTest} を継承し、Kanban 系テストでのみ
 * 必要となる {@link RepairPlanModuleGuard} のモック化を集約する。</p>
 *
 * <h3>なぜ RepairPlanModuleGuard をモック化するか</h3>
 * <ol>
 *   <li>{@code RepairPlanModuleGuardAspect} は {@code @RequireRepairPlanModule} が付与された
 *       Controller メソッドを AOP でインターセプトし、テンプレ／モジュール有効化チェックを行う。</li>
 *   <li>統合テストの目的は Controller〜Service〜DB 間の連携を検証することであり、
 *       モジュール有効化の DB データを全テストで整備するのはコスト過大。</li>
 *   <li>{@link RepairPlanModuleGuard} の動作は専用の単体テスト
 *       ({@code RepairPlanModuleGuardTest}) で別途検証済みのため、ここでは no-op とする。</li>
 * </ol>
 *
 * <p>{@code @EnabledIf} はJUnit 5 の仕様上、継承されないため派生クラスでも再宣言が必要。</p>
 */
public abstract class AbstractRepairPlanKanbanIntegrationTest extends AbstractMySqlIntegrationTest {

    /**
     * ModuleGuard は別途単体テスト済みのため、統合テストでは no-op にする。
     */
    @MockitoBean
    protected RepairPlanModuleGuard repairPlanModuleGuard;

    /**
     * UserEntity の暗号化フィールド (last_name/first_name 等) を平文 INSERT すると
     * JPA 復号で例外を投げる。テスト用ユーザーを native query で投入する際は
     * {@link #encryptForTest(String)} で値を暗号化してから INSERT すること。
     */
    @Autowired
    protected EncryptionService encryptionService;

    /** テストの native INSERT で UserEntity 暗号化フィールドに格納する値を暗号化する。 */
    protected String encryptForTest(String plain) {
        return encryptionService.encrypt(plain);
    }

    /** モジュールガードのモックを no-op にセットアップする。派生クラスの {@code @BeforeEach} で呼ぶこと。 */
    protected void mockModuleGuardNoop() {
        lenient().doNothing().when(repairPlanModuleGuard).requireEnabled(any(), anyLong());
    }
}
