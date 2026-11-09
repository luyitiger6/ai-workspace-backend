package com.luyi.myfirst.service.dm;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.DmErrorEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 私信错误码固定集合（design D8）。
 *
 * 两个方向都要钉：新增一个私信码是契约变更（这里会红），
 * 往 {@link AuthErrorEnum} 里加第八个同样是契约变更（这里也会红）。
 */
class DmErrorEnumTest {

    @Test
    @DisplayName("私信专有错误码只有三个，状态码与 design 一致")
    void dmErrorCodesAreExactlyThree() {
        assertThat(DmErrorEnum.values()).hasSize(3);

        assertThat(DmErrorEnum.DM_SELF_CONVERSATION.getErrorCode()).isEqualTo("DM_SELF_CONVERSATION");
        assertThat(DmErrorEnum.DM_SELF_CONVERSATION.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(DmErrorEnum.DM_PEER_NOT_FOUND.getErrorCode()).isEqualTo("DM_PEER_NOT_FOUND");
        assertThat(DmErrorEnum.DM_PEER_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(DmErrorEnum.DM_CONVERSATION_NOT_FOUND.getErrorCode()).isEqualTo("DM_CONVERSATION_NOT_FOUND");
        assertThat(DmErrorEnum.DM_CONVERSATION_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("共用码仍取自认证侧，映射未漂移")
    void sharedCodesAreReusedFromAuthSide() {
        assertThat(AuthErrorEnum.VALIDATION_FAILED.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(AuthErrorEnum.UNAUTHENTICATED.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("认证侧的七个码数量未变（私信没有偷偷扩它）")
    void authErrorCodesAreUntouched() {
        assertThat(AuthErrorEnum.values()).hasSize(7);
    }
}
