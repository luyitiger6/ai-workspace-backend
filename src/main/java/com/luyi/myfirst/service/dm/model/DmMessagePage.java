package com.luyi.myfirst.service.dm.model;

import java.util.List;

/**
 * 消息列表的一页：条目 + 续页游标。
 *
 * {@code nextCursor} 为 {@code null} 表示没有更多消息——不用空串表达「到底了」，
 * 空串与「从第一页开始」在前端难以区分（与 post 的 {@code PostPage} 同一约定）。
 */
public record DmMessagePage(List<DmMessageView> items, String nextCursor) {
}
