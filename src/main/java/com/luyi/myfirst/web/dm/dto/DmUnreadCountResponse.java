package com.luyi.myfirst.web.dm.dto;

import com.luyi.myfirst.common.web.ApiResponse;

/**
 * 顶栏未读角标的响应体（design D9）：只有一个整数。
 *
 * 为拿这一个数字去拉会话列表（还可能带 50 份名片）不划算，
 * 这里走两侧参与者索引上的一条聚合查询。
 */
public record DmUnreadCountResponse(long count) implements ApiResponse {
}
