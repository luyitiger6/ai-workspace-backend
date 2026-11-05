package com.luyi.myfirst.service.media.support;

import java.util.regex.Pattern;

/**
 * 媒体上传与读取的门槛（design D3/D4）。
 *
 * 上限同时出现在三处，必须一起改：这里的常量、application.yml 的 multipart 限制、
 * 以及前端选择文件时的即时提示。任一处落后都会造成「前端放行、服务端拒绝」的错位。
 */
public final class MediaRules {

    /** 单文件上限（5 MB），与 {@code spring.servlet.multipart.max-file-size} 对齐。 */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    /** 判定三种格式只需头部 12 字节，超出的部分不必读进来做判断。 */
    public static final int MAGIC_BYTES = 12;

    /**
     * 存储标识的形状：26 位 ULID 加大写扩展名。
     *
     * 读取时先过这一层再做路径拼接——任何不合形状的输入（含 {@code ..}、斜杠、百分号）
     * 都在这里被挡下，不给文件系统任何解释路径的机会。
     */
    public static final Pattern FILE_NAME = Pattern.compile("^[0-9A-Z]{26}\\.(jpg|png|webp)$");

    private MediaRules() {
    }
}
