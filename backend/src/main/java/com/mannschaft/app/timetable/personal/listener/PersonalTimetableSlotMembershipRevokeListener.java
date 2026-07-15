package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.15 Phase 4: ユーザーがチームから脱退（または除名）された際に、
 * 自身の個人時間割コマに残っているそのチームへのリンクを自動解除するリスナー。
 *
 * <p>設計書 §5.4 を参照。コマ自体とメモ・添付ファイルは保持する（リンク列のみ NULL クリア）。</p>
 *
 * <h2>認可根治戦役 AC-1-1d 500 根治（コネクション増幅の除去）</h2>
 * <p>本リスナーは <b>同期</b> {@link EventListener}（{@code @TransactionalEventListener} ではない）
 * であり、{@code MembershipChangedEvent} の {@code publishEvent} 時に発火元スレッド（＝ロール変更の
 * リクエスト処理スレッド）上でその場で実行される。加えて {@code @Transactional(REQUIRES_NEW)} が
 * <b>メソッド全体</b>を包むため、トランザクション proxy は本文の {@code REMOVED} 判定に到達する<b>前に</b>
 * 外側トランザクションを suspend し、Hikari プールから<b>2 本目のコネクションを取得</b>して新規
 * トランザクションを開始する。</p>
 *
 * <p>その結果、{@code changeRole}/{@code assignRole}/{@code transferOwnership} が発火する
 * {@code ASSIGNED}/{@code CHANGED} イベント（＝実際には本リスナーが何もしない no-op ケース）でも、
 * ロール変更のたびに同期で 2 本目のコネクション取得＋空トランザクションのコミットが走っていた。
 * full-shard（shard3）でコネクションプールが逼迫すると、この 2 本目取得が
 * {@code CannotCreateTransactionException}（Connection is not available）で失敗し、
 * 「正当 ADMIN の昇格（AC-1-1d 正常系）」だけが非決定的に 500 になっていた
 * （403/BOLA 系は {@code requireActorAdmin} でイベント発火前に弾かれるため無傷、単独 shard は逼迫が無く緑）。</p>
 *
 * <p><b>根治</b>: {@link EventListener#condition() condition}（SpEL）で「TEAM かつ REMOVED」の場合のみ
 * リスナーメソッド自体を呼び出すよう絞り込む。condition は Spring のイベント基盤がメソッド呼び出し（＝
 * トランザクション proxy 起動）<b>より前</b>に評価するため、no-op の {@code ASSIGNED}/{@code CHANGED} では
 * REQUIRES_NEW トランザクションも 2 本目コネクション取得も一切発生しない。本番のロール変更ホットパスから
 * 不要なコネクション増幅を除去する実挙動修正であり、REMOVED 時の自動リンク解除は従来通り維持される。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalTimetableSlotMembershipRevokeListener {

    private final PersonalTimetableSlotRepository personalSlotRepository;

    /**
     * チーム脱退（{@code REMOVED} かつ {@code TEAM}）イベントのみを受信してリンクを自動解除する。
     *
     * <p>condition で TEAM＋REMOVED に限定しているため、メソッド本体（および包む REQUIRES_NEW
     * トランザクション）は実際にリンク解除の可能性がある場合にのみ起動される。{@code scopeType}/
     * {@code changeType} は {@link MembershipChangedEvent} のコンパクトコンストラクタで非 null が
     * 保証されるため、SpEL で NPE の懸念はない。</p>
     */
    @EventListener(condition = "#event.changeType().name() == 'REMOVED' && #event.scopeType().equalsIgnoreCase('TEAM')")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMembershipChanged(MembershipChangedEvent event) {
        // condition で TEAM＋REMOVED に限定済みだが、直接呼び出し等に備えた防御としてガードを残す（no-op で無害）。
        if (event.changeType() != MembershipChangedEvent.ChangeType.REMOVED) {
            return;
        }
        if (!"TEAM".equalsIgnoreCase(event.scopeType())) {
            return;
        }

        try {
            List<PersonalTimetableSlotEntity> slots = personalSlotRepository
                    .findByLinkedTeamIdAndOwnerUserId(event.userId(), event.scopeId());
            for (PersonalTimetableSlotEntity s : slots) {
                s.unlink();
            }
            if (!slots.isEmpty()) {
                personalSlotRepository.saveAll(slots);
                log.info("チーム脱退に伴うリンク自動解除: userId={}, teamId={}, slotCount={}",
                        event.userId(), event.scopeId(), slots.size());
            }
        } catch (Exception ex) {
            log.error("PersonalTimetableSlotMembershipRevokeListener 失敗: userId={}, teamId={}, error={}",
                    event.userId(), event.scopeId(), ex.getMessage(), ex);
        }
    }
}
