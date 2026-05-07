package com.mannschaft.app.todo;

/**
 * TODO ステータスラベルのバケット種別（F02.3.1）。
 *
 * <p>ユーザー定義ステータスラベルは必ずこの3バケットのいずれかに分類され、
 * バケットから {@link TodoStatus} へ一意にマッピングされる。</p>
 */
public enum TodoStatusBucket {
    /** 未着手 */
    OPEN,
    /** 着手中 */
    IN_PROGRESS,
    /** 完了 */
    COMPLETED;

    /**
     * バケットから対応する {@link TodoStatus} を返す。
     *
     * @return マッピング先の TodoStatus
     */
    public TodoStatus toTodoStatus() {
        switch (this) {
            case OPEN:
                return TodoStatus.OPEN;
            case IN_PROGRESS:
                return TodoStatus.IN_PROGRESS;
            case COMPLETED:
                return TodoStatus.COMPLETED;
            default:
                throw new IllegalStateException("未対応のバケット: " + this);
        }
    }

    /**
     * {@link TodoStatus} から対応するバケットを返す。
     * CANCELLED は3バケットに含まれないため例外を投げる（呼び出し側で除外すること）。
     *
     * @param status TodoStatus
     * @return 対応する TodoStatusBucket
     */
    public static TodoStatusBucket fromTodoStatus(TodoStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status は必須です");
        }
        switch (status) {
            case OPEN:
                return OPEN;
            case IN_PROGRESS:
                return IN_PROGRESS;
            case COMPLETED:
                return COMPLETED;
            case CANCELLED:
            default:
                throw new IllegalArgumentException("バケット非対応の TodoStatus: " + status);
        }
    }
}
