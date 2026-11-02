package com.luyi.myfirst.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 媒体存储的可调参数（application.yml 的 wanderchain.media）。
 *
 * 与 {@link AuthProperties} 同样刻意不给代码内默认值：缺一项就绑定失败、启动即失败，
 * 避免「改了配置却因代码里有兜底而没生效」。
 *
 * root 是落盘目录，相对路径按进程工作目录解析；publicBaseUrl 是上传返回地址的前缀，
 * 默认指向前端同源代理（{@code http://localhost:3000/api/media}），使图片与页面同源。
 * 换域名时必须同步改这一项，否则历史封面地址会失效。
 */
@Validated
@ConfigurationProperties(prefix = "wanderchain.media")
public record MediaProperties(
        @NotBlank String root,
        @NotBlank String publicBaseUrl
) {
}
