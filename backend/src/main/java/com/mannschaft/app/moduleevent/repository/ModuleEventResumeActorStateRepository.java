package com.mannschaft.app.moduleevent.repository;

import com.mannschaft.app.moduleevent.admin.ModuleEventResumeActorStateEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** moduleevent内のactor再生防止状態を扱う。業務上のupsert/lockは試練red確認後に追加する。 */
public interface ModuleEventResumeActorStateRepository
        extends JpaRepository<ModuleEventResumeActorStateEntity, UUID> {
}
