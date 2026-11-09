package com.luyi.myfirst.web.dm.dto;

/**
 * 发送消息的请求体：正文原样传入，长度与空白判定在服务层统一处理。
 *
 * 不在这一层 trim：spec 要求「去除首尾空白后不为空」是拒绝条件，
 * 不是「先替用户 trim 再存」——原文入库由 DmInputRules 保证。
 */
public record SendMessageRequest(String content) {
}
