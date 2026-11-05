package com.luyi.myfirst.service.media.model;

import java.nio.file.Path;

/**
 * 一次成功读取的结果。
 *
 * 带出的是 {@link Path} 而不是 Spring 的 {@code Resource}：service 层不引用 web 层与 MVC 类型，
 * 由控制器决定怎么把文件写进响应。
 */
public record LoadedMedia(Path path, String contentType, long size) {
}
