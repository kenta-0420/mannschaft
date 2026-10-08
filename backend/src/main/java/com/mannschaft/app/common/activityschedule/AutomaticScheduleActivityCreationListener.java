package com.mannschaft.app.common.activityschedule;

import com.mannschaft.app.activity.service.AutomaticScheduleActivityService;
import com.mannschaft.app.schedule.event.ScheduleActivityCreationEvent;
import com.mannschaft.app.schedule.service.ScheduleActivitySourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 予定と自動記録の保存を原子的にする共通段取り役。
 * 同期event＋MANDATORYで予定writerのTXへ参加し、活動失敗時は予定・具体子もrollbackする。
 * ScheduleServiceへ逆依存せず、通知の既存after-commit経路は変更しない。
 */
@Component
@RequiredArgsConstructor
public class AutomaticScheduleActivityCreationListener {
    private final ScheduleActivitySourceService sources;
    private final AutomaticScheduleActivityService activities;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCreated(ScheduleActivityCreationEvent event) {
        // 明示manual作成・内部再実行とも予定行を先にlockし、同じIDの重複を直列化する。
        var current = sources.currentSources(List.of(event.scheduleId()), true);
        if (!current.isEmpty()) activities.createAutomatic(current.getFirst(), event.createdBy());
    }
}
