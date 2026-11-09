package com.luyi.myfirst.service.dm.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;

/**
 * 私信正文校验（design D5，spec「文本消息校验」）。
 *
 * 长度单位是**字符**（{@link String#codePointCount}），与 MySQL {@code VARCHAR(2000)}
 * 按字符计的语义一致，emoji 不会按字节或 UTF-16 单元被算成两个。
 *
 * 原文入库、不 trim：spec 只要求「去除首尾空白后不为空」，没要求改变存储值；
 * 静默改写用户输入会让「发送的」与「显示的」不一致。
 *
 * 这里是写路径上唯一的校验点，服务层与 web 层都不再重复判断。
 */
public final class DmInputRules {

    public static final int CONTENT_MAX_CHARS = 2000;

    private DmInputRules() {
    }

    /**
     * 校验并返回待入库的原文；null、纯空白、超长一律 {@code VALIDATION_FAILED}（400）。
     *
     * 这些条件与调用方身份无关，不能借用 403/404 表达，否则「参数不对」和
     * 「无权操作」在前端会变成同一个处理分支。
     */
    public static String requireContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw rejected();
        }
        int chars = raw.codePointCount(0, raw.length());
        if (chars < 1 || chars > CONTENT_MAX_CHARS) {
            throw rejected();
        }
        return raw;
    }

    private static BizException rejected() {
        return new BizException(AuthErrorEnum.VALIDATION_FAILED);
    }
}
