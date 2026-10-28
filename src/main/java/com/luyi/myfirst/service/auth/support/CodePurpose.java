package com.luyi.myfirst.service.auth.support;

/**
 * 验证码的去向。
 *
 * 注册码与重置码共用同一套生成、存储与比对机制（D6），只有验证通过后的动作不同；
 * 这个枚举就是那唯一一处不同的标记，出现在投递记录里，让测试与开发期工具能分清。
 */
public enum CodePurpose {

    REGISTER,

    PASSWORD_RESET
}
