package com.luyi.myfirst.service.media.model;

/** 一次成功上传的结果：对外地址、按内容判定的类型、落盘字节数。 */
public record StoredMedia(String url, String contentType, long size) {
}
