package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import org.springframework.data.jpa.domain.Specification;

/** F14.1 代理入力: ScheduleAttendance 集計分離用 Specification */
public final class ScheduleAttendanceSpecs {

    private ScheduleAttendanceSpecs() {}

    /** 本人入力のみ（is_proxy_input = false）— 出席率・定足数判定に使用 */
    public static Specification<ScheduleAttendanceEntity> byPersonOnly() {
        return (root, query, cb) -> cb.isFalse(root.get("isProxyInput"));
    }

    /** 代理入力を含む全件 */
    public static Specification<ScheduleAttendanceEntity> includingProxy() {
        return (root, query, cb) -> cb.conjunction();
    }
}
