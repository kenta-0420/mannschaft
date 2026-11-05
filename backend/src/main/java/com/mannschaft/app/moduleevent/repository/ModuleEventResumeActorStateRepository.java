package com.mannschaft.app.moduleevent.repository;

import com.mannschaft.app.moduleevent.admin.ModuleEventResumeActorStateEntity;
import jakarta.persistence.QueryHint;
import java.util.UUID;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** moduleevent内のactor再生防止状態を扱う。各操作は呼出側の同一TXで行う。 */
public interface ModuleEventResumeActorStateRepository
        extends JpaRepository<ModuleEventResumeActorStateEntity, UUID> {

    /** 重複時は既存idとpurgedを保ち、同actorの初回作成も排他化する。 */
    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = """
            INSERT INTO module_event_resume_actor_states (id, actor_user_id, purged)
            VALUES (UUID_TO_BIN(:id), :actorUserId, false)
            ON DUPLICATE KEY UPDATE actor_user_id = module_event_resume_actor_states.actor_user_id
            """, nativeQuery = true)
    int createIfAbsent(@Param("id") String id, @Param("actorUserId") Long actorUserId);

    /** L1のEntityを使わず、locking current readで最新の不可逆状態を読む。 */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = """
            SELECT CAST(purged AS UNSIGNED) FROM module_event_resume_actor_states
            WHERE actor_user_id = :actorUserId FOR UPDATE
            """, nativeQuery = true)
    Long lockPurged(@Param("actorUserId") Long actorUserId);

    /** ロック取得後だけ呼び出し、falseへ戻す経路を設けない。 */
    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = """
            UPDATE module_event_resume_actor_states SET purged = true WHERE actor_user_id = :actorUserId
            """, nativeQuery = true)
    int markPurged(@Param("actorUserId") Long actorUserId);
}
