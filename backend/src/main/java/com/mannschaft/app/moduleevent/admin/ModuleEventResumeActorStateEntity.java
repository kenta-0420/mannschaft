package com.mannschaft.app.moduleevent.admin;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 強匿名化完了後の理由再生を防ぐ、moduleevent所有の永続状態。
 * 認証状態を複製せず、監査の保持期限後も消去済み状態を保持する。
 */
@Entity
@Table(name = "module_event_resume_actor_states", uniqueConstraints =
        @UniqueConstraint(name = "uq_module_event_resume_actor_states_actor_user_id", columnNames = "actor_user_id"))
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModuleEventResumeActorStateEntity extends UuidV7Entity {
    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(nullable = false)
    private boolean purged;
}
