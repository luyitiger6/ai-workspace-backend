package com.luyi.myfirst.common.enums;

/**
 * 账户三态。
 *
 * 迁移只有一条：ACTIVE --本人注销--> DELETED。DELETED 是账户行的终态，
 * 但它不阻止同一邮箱注册出新的账户行——终态约束的是行，不是邮箱。
 * EMAIL_UNVERIFIED 是保留值：本变更既无入口也无出口，只有测试夹具能造出它。
 */
public enum AccountStatus {

    ACTIVE("active"),
    EMAIL_UNVERIFIED("email_unverified"),
    DELETED("deleted");

    private final String dbValue;

    AccountStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 与 schema.sql 中 CHECK 约束的取值一一对应，不是枚举名的大小写转换。 */
    public String dbValue() {
        return dbValue;
    }

    /**
     * 是否允许从当前状态迁移到 {@code target}。
     *
     * 注册建立账户不经过这里——那是构造新行，不是迁移。
     */
    public boolean canTransitionTo(AccountStatus target) {
        return this == ACTIVE && target == DELETED;
    }
}
