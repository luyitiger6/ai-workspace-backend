package com.luyi.myfirst.service.post;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.PostErrorEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子错误码固定集合（spec「帖子错误码取自固定集合」）。
 *
 * 两个方向都要钉：新增一个帖子码是契约变更（这里会红），
 * 往 {@link AuthErrorEnum} 里加第八个同样是契约变更（这里也会红）。
 */
class PostErrorEnumTest {

    @Test
    @DisplayName("帖子专有错误码只有两个，状态码与 spec 一致")
    void postErrorCodesAreExactlyTwo() {
        assertThat(PostErrorEnum.values()).hasSize(2);
        assertThat(PostErrorEnum.POST_FORBIDDEN.getErrorCode()).isEqualTo("POST_FORBIDDEN");
        assertThat(PostErrorEnum.POST_FORBIDDEN.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(PostErrorEnum.POST_NOT_FOUND.getErrorCode()).isEqualTo("POST_NOT_FOUND");
        assertThat(PostErrorEnum.POST_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("三个共用码仍取自认证侧，映射未漂移")
    void sharedCodesAreReusedFromAuthSide() {
        assertThat(AuthErrorEnum.UNAUTHENTICATED.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AuthErrorEnum.CROSS_SITE_REJECTED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(AuthErrorEnum.VALIDATION_FAILED.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("认证侧的七个码数量未变（帖子没有偷偷扩它）")
    void authErrorCodesAreUntouched() {
        assertThat(AuthErrorEnum.values()).hasSize(7);
    }
}
