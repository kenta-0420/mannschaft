package com.mannschaft.app.notification;

import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 一括通知のバルク INSERT 経路が、<b>配信するエンティティに DB 採番 id を戻してから</b>
 * {@link NotificationDispatchService#dispatchBatch} へ渡すことの回帰ガード。
 *
 * <p>リアルタイム配信（WebSocket/Push）のクライアントは、受信フレームの {@code id} が数値であることを
 * 前提に取り込む。バルク INSERT で JPA を迂回する際に採番 id をエンティティへ戻し損ねると、配信ペイロードの
 * {@code id} が欠落し、クライアントがフレームを取り込めなくなる。DB 行は id 付きで正しく作られる一方で
 * リアルタイム配信だけが無効化される死角を、配信エンティティの id を直接検証して塞ぐ。</p>
 *
 * <p>{@link NotificationDispatchService} をモック化して {@code dispatchBatch} の引数を捕捉し、
 * バルク INSERT（実 MySQL・auto_increment）で採番された id が全件・エンティティに載っていることを固定する。</p>
 */
@DisplayName("一括通知バルク経路は配信エンティティに採番 id を戻す")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationBulkFanoutGeneratedKeyIT extends AbstractMySqlIntegrationTest {

    @MockitoBean
    private NotificationDispatchService dispatchService;

    @Autowired
    private NotificationHelper notificationHelper;

    @Test
    @DisplayName("バルク INSERT で dispatch されるエンティティは全件、非null の数値 id を持つ")
    void bulkDispatchedEntitiesCarryGeneratedNumericId() {
        // notifications は user_id にクロスドメイン FK を持たない（撤廃済）ため、任意の高位レンジで検証できる。
        List<Long> recipients = List.of(951_000_001L, 951_000_002L, 951_000_003L);

        notificationHelper.notifyAllPreAuthorized(
                recipients, "EVENT_CREATED", "村の行事案内", "新しい行事が追加されました",
                "VILLAGE_EVENT", null, NotificationScopeType.SYSTEM, null, "/villages/x", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationEntity>> captor =
                (ArgumentCaptor<List<NotificationEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        // dispatchBatch はモック（同期）ゆえ、notifyAllPreAuthorized 返却時点で捕捉できる。
        verify(dispatchService).dispatchBatch(captor.capture());

        List<NotificationEntity> dispatched = captor.getValue();
        assertThat(dispatched).hasSize(recipients.size());
        assertThat(dispatched)
                .as("配信エンティティは全件、バルク INSERT で採番された非null の id を持つ（id 欠落フレームは配信で破棄される）")
                .allSatisfy(n -> assertThat(n.getId()).isNotNull());
        assertThat(dispatched)
                .extracting(NotificationEntity::getId)
                .as("採番 id は数値（Long）")
                .allSatisfy(id -> assertThat(id).isInstanceOf(Long.class));
    }
}
