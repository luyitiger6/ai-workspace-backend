package com.luyi.myfirst.service.post.model;

import com.luyi.myfirst.common.enums.PostStatus;

import java.util.List;

/**
 * 一次写入要覆盖的全部内容（发布与编辑共用）。
 *
 * 没有作者字段：作者一律取自令牌（design D6），请求体里带了也不采信。
 * 这里存的是**已校验**的值——{@link com.luyi.myfirst.service.post.support.PostInputRules}
 * 负责 trim、去重与长度门槛，本记录不做二次判断。
 */
public record PostCommand(String title,
                          String content,
                          String coverImage,
                          List<String> tags,
                          PostStatus status) {
}
