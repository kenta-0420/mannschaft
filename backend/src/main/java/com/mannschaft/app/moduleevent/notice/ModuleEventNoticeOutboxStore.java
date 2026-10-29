package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知予約の DB 契約骨格。実 DB の assertion red 確認前に動作実装しない。
 * この型は内部用であり notification へ Entity を公開しない。
 * reserve は呼出し元 M TX、claim/更新は M 専用 TX で接続する段階まで未実装。
 */
public final class ModuleEventNoticeOutboxStore {
    public static final int MAX_CLAIMS = 100;

    public ModuleEventNoticeOutboxStore(EntityManager entityManager) {
        Objects.requireNonNull(entityManager, "entityManager");
    }

    /** 同一 noticeKey の再予約は同じ ID/noticeRef を返し、異なる descriptor は拒否する。
     * 再予約で既存 status / lease / retry 時刻を初期化しない。 */
    public Optional<ModuleEventNoticeOutboxEntity> reserve(ModuleEventNoticeOutboxEntity reservation) {
        return Optional.empty();
    }

    /**
     * 成功削除と同じ M TX に参加し、同じ binding / audience の既存 DELAYED 又は
     * QUARANTINED を current read した場合だけ RECOVERED を WAITING_RECOVERY で予約する。
     * 通常の高速成功は empty。業務 SPI を呼ばず、失敗は削除 TX ごと rollback する。
     * Spring 接続時は MANDATORY とし、外側成功 hook の TX と原子的に確定する。
     */
    public Optional<ModuleEventNoticeOutboxEntity> reserveRecoveryIntent(
            ModuleEventNoticeOutboxEntity recoveryReservation) {
        return Optional.empty();
    }

    /** SQL 自体を limit 件に制限。未知版を先に判定し、後続の既知版を停止させない。 */
    public List<ModuleEventNoticeOutboxEntity> claim(int limit, Instant now) {
        return List.of();
    }

    /** WAITING_RECOVERY だけを SQL 上限 1..100 件で取得し、新しい評価 token を採番する。 */
    public List<ModuleEventNoticeOutboxEntity> claimRecovery(int limit, Instant now) {
        return List.of();
    }

    /**
     * M 読取 TX 終了後に得た業務評価を別 M TX へ戻す。SPI 呼出しを内包しない。
     * 現 status / token が一致し、CLOSED_COMPLETE かつ M pending=0 を再確認した場合だけ
     * PENDING へ昇格する。OPEN / UNKNOWN は WAITING の次回時刻へ繰り延べる。
     * 評価の繰延べで relay attemptCount を増やさない。CLOSED 後の同 episode 再利用は禁止。
     */
    public boolean applyRecoveryEvaluation(UUID id, UUID claimToken, ModuleEventRecoveryState recovery,
            Instant nextAttemptAt, Instant now) {
        return false;
    }

    /** id と現在の claimToken と RELAYING を同時比較し、旧世代の成功を拒否する。
     * RELAYED 後も token を保持し、二段 retention の世代 CAS に用いる。 */
    public boolean markRelayed(UUID id, UUID claimToken, Instant now) {
        return false;
    }

    /** 未知版は閉じた enum へ変換せず、現在世代だけ次回時刻へ繰り延べる。 */
    public boolean deferUnsupportedVersion(UUID id, UUID claimToken, Instant nextAttemptAt, Instant now) {
        return false;
    }

    /** 期限切れ lease だけを有界処理する。WAITING は WAITING のまま評価 lease を失効させる。
     * RELAYING は PENDING へ戻し、どちらも旧 token を無効化する。次 claim は新 token。 */
    public int recoverStuck(Instant claimedBefore, Instant now) {
        return 0;
    }
}
