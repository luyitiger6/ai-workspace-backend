package com.luyi.myfirst.service.auth.support;

/**
 * 验证码的去向。
 *
 * 注册码与重置码共用同一套生成、存储与比对机制（D6），只有验证通过后的动作不同；
 * 这个枚举就是那唯一一处不同的标记，出现在投递记录里，让测试与开发期工具能分清。
 */
public enum CodePurpose {

    REGISTER("register"),

    PASSWORD_RESET("reset");

    private final String key;

    CodePurpose(String key) {
        this.key = key;
    }

    /**
     * 缓存键与摘要前缀里用的用途段。
     *
     * 键按用途分段，是为了让一个用途的码挪不到另一个端点上去用：注册码与重置码的
     * 生成、比对、限流完全同构，若共用一个键，「两个端点各自只认自己那类码」就只剩口头约定。
     */
    public String key() {
        return key;
    }
}
