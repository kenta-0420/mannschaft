package com.mannschaft.app.member.service;

import com.mannschaft.app.member.entity.TeamPageEntity;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * PR #3387 試練: {@link TeamPageService} の package-private な認可判定メソッドを、名前で呼び出す補助。
 *
 * <p>試練の時点では {@code checkPageViewableOrNotFound}・{@code checkPageMemberRoleOrNotFound} が
 * まだ実装されていない。テストから直接呼ぶとテストソース全体のコンパイルが止まり、無関係な
 * テストまで走らなくなるため、リフレクションで呼ぶ。メソッドが無いときは
 * {@link AssertionError}（「未実装」）で落ちるので、red の理由がそのまま読める。</p>
 *
 * <p>メソッドの存在確認は Mockito の verify/スタブを始める前に行う（検証の途中で落ちて
 * Mockito の状態を壊さないため）。出陣で実装したあとは、直接呼び出しに書き換えてよい。</p>
 */
public final class PageAuthzProbe {

    /** 閲覧経路の判定（V1〜V4）。 */
    public static final String VIEWABLE = "checkPageViewableOrNotFound";

    /** 操作経路の判定（O1 lookup・O2 copy のコピー元）。MEMBER 以上でなければ 404。 */
    public static final String MEMBER_ROLE = "checkPageMemberRoleOrNotFound";

    /** base と同一に戻す共通の会員確認。 */
    public static final String MEMBERSHIP = "checkPageMembershipOrNotFound";

    private PageAuthzProbe() {
    }

    /**
     * 実物（またはスパイ）の {@code TeamPageService#<name>(Long, TeamPageEntity)} を呼ぶ。
     * 判定が投げた例外（BusinessException 等）はそのまま投げ直す。
     */
    public static void call(TeamPageService target, String methodName, Long actorUserId, TeamPageEntity page) {
        invoke(target, find(methodName), actorUserId, page);
    }

    /**
     * モックに対し {@code verify(mock, mode).<name>(actor, page)} を行う。
     */
    public static void verifyCalled(TeamPageService mock, VerificationMode mode,
                                    String methodName, Long actorUserId, TeamPageEntity page) {
        Method method = find(methodName);
        invoke(Mockito.verify(mock, mode), method, actorUserId, page);
    }

    /**
     * モックに対し {@code doThrow(ex).when(mock).<name>(actor, page)} を行う。
     */
    public static void stubThrow(TeamPageService mock, RuntimeException ex,
                                 String methodName, Long actorUserId, TeamPageEntity page) {
        Method method = find(methodName);
        invoke(Mockito.doThrow(ex).when(mock), method, actorUserId, page);
    }

    /**
     * メソッドを名前で探す。無ければ「未実装」として AssertionError を投げる。
     */
    public static Method find(String methodName) {
        try {
            Method method = TeamPageService.class.getDeclaredMethod(methodName, Long.class, TeamPageEntity.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("未実装: TeamPageService#" + methodName
                    + "(Long, TeamPageEntity) が存在しない（PR #3387 試練の red）", e);
        }
    }

    private static void invoke(TeamPageService target, Method method, Long actorUserId, TeamPageEntity page) {
        try {
            method.invoke(target, actorUserId, page);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
