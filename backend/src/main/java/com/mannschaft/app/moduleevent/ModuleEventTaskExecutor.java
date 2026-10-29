package com.mannschaft.app.moduleevent;

import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** M専用の受付と世代搬送を検証するexecutor。既存Beanへの接続は別段階で行う。 */
public final class ModuleEventTaskExecutor extends ThreadPoolTaskExecutor {
    private final ModuleEventExecutionFrame.Context context;
    private final ModuleEventExecutionFrame.Owner owner;
    private final int admissionLimit;

    public ModuleEventTaskExecutor(ModuleEventExecutionFrame.Context context,
            ModuleEventExecutionFrame.Owner owner, int admissionLimit, TaskDecorator delegate) {
        this.context = context;
        this.owner = owner;
        this.admissionLimit = admissionLimit;
        setTaskDecorator(delegate);
    }

    public int availableCapacity() {
        return 0;
    }
}
