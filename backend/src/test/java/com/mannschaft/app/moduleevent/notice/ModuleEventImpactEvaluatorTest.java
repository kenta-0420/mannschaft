package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticePhase;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 有界な現在受信者評価と非TX呼出し契約の試練。実装前のactual assertion redからgreenへ進める。 */
class ModuleEventImpactEvaluatorTest {
    private static final String KEY = "fixture.notice";
    private static final ModuleEventImpactBinding BINDING = new ModuleEventImpactBinding(KEY,
            UUID.fromString("019a0000-0000-7000-8000-000000000001"),
            UUID.fromString("019a0000-0000-7000-8000-000000000002"), ModuleEventNoticeScope.TEAM, 7L, null);
    private static final ModuleEventNoticeDescriptor NOTICE = new ModuleEventNoticeDescriptor(
            UUID.fromString("019a0000-0000-7000-8000-000000000003"), 1L, BINDING,
            ModuleEventNoticePhase.DELAYED, ModuleEventNoticeAudience.CURRENT_ADMINS);

    @AfterEach
    void caller状態を残さない() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void 未接続は正常終端と区別() {
        ModuleEventImpactEvaluator evaluator = new ModuleEventImpactEvaluator(new ModuleEventImpactResolverRegistry(List.of()));
        assertEquals(ModuleEventRecoveryState.UNKNOWN, evaluator.recoveryState(BINDING));
        assertTrue(evaluator.nextRecipients(NOTICE, 0L, 1).isEmpty());
        assertTrue(evaluator.eligibleRecipients(NOTICE, List.of(1L)).isEmpty());
    }

    @Test
    void 接続済の空ページは正常終端() {
        assertEquals(List.of(), evaluator(List.of()).nextRecipients(NOTICE, 0L, 1).orElse(null));
    }

    @Test
    void 正常pageと候補の再検証を返す() {
        List<ModuleEventNoticeRecipient> rows = List.of(recipient(2L), recipient(3L));
        assertEquals(rows, evaluator(rows).nextRecipients(NOTICE, 1L, 2).orElse(null));
        assertEquals(rows, evaluator(rows).eligibleRecipients(NOTICE, List.of(2L, 3L)).orElse(null));
    }

    @Test
    void 返却リストを呼出側が書き換えられない() {
        List<ModuleEventNoticeRecipient> rows = new ArrayList<>(List.of(recipient(2L)));
        List<ModuleEventNoticeRecipient> result = evaluator(rows).nextRecipients(NOTICE, 0L, 1).orElse(null);
        rows.clear();
        assertNotNull(result, "接続済 provider の結果は未接続と区別する");
        assertEquals(1, result.size());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test
    void null返却を正常な空ページへ偽装しない() {
        assertThrows(IllegalStateException.class, () -> evaluator(null).nextRecipients(NOTICE, 0L, 1));
        assertThrows(IllegalStateException.class, () -> evaluator(null).eligibleRecipients(NOTICE, List.of(1L)));
    }

    @Test
    void null要素を拒否() {
        assertThrows(IllegalStateException.class,
                () -> evaluator(Arrays.asList((ModuleEventNoticeRecipient) null)).nextRecipients(NOTICE, 0L, 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void userId不正の受信者を拒否(long userId) {
        assertThrows(IllegalStateException.class, () -> evaluator(List.of(recipient(userId))).nextRecipients(NOTICE, 0L, 1));
    }

    @Test
    void 重複と未ソートを拒否() {
        assertThrows(IllegalStateException.class,
                () -> evaluator(List.of(recipient(2L), recipient(2L))).nextRecipients(NOTICE, 0L, 2));
        assertThrows(IllegalStateException.class,
                () -> evaluator(List.of(recipient(3L), recipient(2L))).nextRecipients(NOTICE, 0L, 2));
    }

    @Test
    void cursor以前とpage上限超過を拒否() {
        assertThrows(IllegalStateException.class, () -> evaluator(List.of(recipient(2L))).nextRecipients(NOTICE, 2L, 1));
        assertThrows(IllegalStateException.class,
                () -> evaluator(List.of(recipient(2L), recipient(3L))).nextRecipients(NOTICE, 0L, 1));
    }

    @Test
    void 再検証で候補外と重複と未ソートと上限超を拒否() {
        assertThrows(IllegalStateException.class,
                () -> evaluator(List.of(recipient(3L))).eligibleRecipients(NOTICE, List.of(2L)));
        assertThrows(IllegalStateException.class,
                () -> evaluator(List.of(recipient(2L), recipient(2L))).eligibleRecipients(NOTICE, List.of(2L, 3L)));
        assertThrows(IllegalStateException.class,
                () -> evaluator(List.of(recipient(3L), recipient(2L))).eligibleRecipients(NOTICE, List.of(2L, 3L)));
        assertThrows(IllegalStateException.class,
                () -> evaluator(List.of(recipient(2L), recipient(3L))).eligibleRecipients(NOTICE, List.of(2L)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501})
    void pageの不正上限をproviderへ渡さない(int limit) {
        assertThrows(IllegalArgumentException.class, () -> evaluator(List.of()).nextRecipients(NOTICE, 0L, limit));
    }

    @Test
    void 候補500件は受け501件は拒否() {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= 500; id++) { ids.add(id); }
        assertEquals(List.of(), evaluator(List.of()).eligibleRecipients(NOTICE, ids).orElse(null));
        ids.add(501L);
        assertThrows(IllegalArgumentException.class, () -> evaluator(List.of()).eligibleRecipients(NOTICE, ids));
    }

    @Test
    void null入力と不正候補と負cursorを拒否() {
        assertThrows(IllegalArgumentException.class, () -> evaluator(List.of()).nextRecipients(null, 0L, 1));
        assertThrows(IllegalArgumentException.class, () -> evaluator(List.of()).nextRecipients(NOTICE, -1L, 1));
        assertThrows(IllegalArgumentException.class, () -> evaluator(List.of()).eligibleRecipients(NOTICE, null));
        assertThrows(IllegalArgumentException.class, () -> evaluator(List.of()).eligibleRecipients(NOTICE, List.of(0L)));
        assertThrows(IllegalArgumentException.class, () -> evaluator(List.of()).eligibleRecipients(NOTICE, List.of(1L, 1L)));
    }

    @Test
    void callerTX保持中は三評価とも拒否() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class, () -> evaluator(List.of()).recoveryState(BINDING));
        assertThrows(IllegalStateException.class, () -> evaluator(List.of()).nextRecipients(NOTICE, 0L, 1));
        assertThrows(IllegalStateException.class, () -> evaluator(List.of()).eligibleRecipients(NOTICE, List.of(1L)));
    }

    @Test
    void 業務復旧の三状態をそのまま返しnullは拒否() {
        for (ModuleEventRecoveryState state : ModuleEventRecoveryState.values()) {
            assertEquals(state, new ModuleEventImpactEvaluator(new ModuleEventImpactResolverRegistry(
                    List.of(provider(List.of(), state)))).recoveryState(BINDING));
        }
        assertThrows(IllegalStateException.class, () -> new ModuleEventImpactEvaluator(new ModuleEventImpactResolverRegistry(
                List.of(provider(List.of(), null)))).recoveryState(BINDING));
    }

    private static ModuleEventImpactEvaluator evaluator(List<ModuleEventNoticeRecipient> rows) {
        return new ModuleEventImpactEvaluator(new ModuleEventImpactResolverRegistry(List.of(provider(rows, ModuleEventRecoveryState.OPEN))));
    }

    private static ModuleEventNoticeRecipient recipient(long id) {
        return new ModuleEventNoticeRecipient(id, "ja");
    }

    private static ModuleEventImpactResolver provider(List<ModuleEventNoticeRecipient> rows, ModuleEventRecoveryState recovery) {
        return new ModuleEventImpactResolver() {
            public String resolverKey() { return KEY; }
            public Optional<ModuleEventImpactBinding> identify(ReliableEvent event) { return Optional.of(BINDING); }
            public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) { return recovery; }
            public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor notice, long after, int limit) { return rows; }
            public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor notice, List<Long> ids) { return rows; }
        };
    }
}
