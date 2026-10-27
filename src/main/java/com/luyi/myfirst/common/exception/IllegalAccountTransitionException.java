package com.luyi.myfirst.common.exception;

import com.luyi.myfirst.common.enums.AccountStatus;

/**
 * 状态机之外的迁移被拒绝。
 *
 * 这不是业务拒绝而是不变量被破坏：只要迁移都走具名方法，它就不该出现。
 * 因此它不实现 {@link BaseError}——对外错误码集合由 spec 钉死，
 * 不能为编程错误挤进去一个第八个码。
 */
public class IllegalAccountTransitionException extends IllegalStateException {

    public IllegalAccountTransitionException(AccountStatus from, AccountStatus to) {
        super("账户状态不能从 " + describe(from) + " 迁移到 " + describe(to));
    }

    private static String describe(AccountStatus status) {
        return status == null ? "null" : status.dbValue();
    }
}
