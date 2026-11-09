package com.luyi.myfirst.service.dm.model;

import java.util.List;

/**
 * 会话列表：按最近活跃时间倒序的一页，没有会话时是空列表而不是错误。
 *
 * 不分页：单用户的会话量级在数十到数百（design D3），用 limit 截断即可；
 * 真的需要翻页时再补游标，而不是现在就给一个未验证的瓶颈加契约。
 */
public record DmConversationPage(List<DmConversationSummary> items) {
}
