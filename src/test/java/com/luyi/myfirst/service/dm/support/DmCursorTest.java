package com.luyi.myfirst.service.dm.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消息游标的编解码（design D4）。
 *
 * 只编码自增 id 一段：空游标表示第一页，其余非法输入一律 VALIDATION_FAILED。
 */
class DmCursorTest {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    @Test
    @DisplayName("正常往返")
    void roundTrip() {
        DmCursor decoded = DmCursor.decode(new DmCursor(42L).encode());

        assertThat(decoded).isEqualTo(new DmCursor(42L));
        assertThat(decoded.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("空游标返回 null，表示第一页")
    void blankMeansFirstPage() {
        assertThat(DmCursor.decode(null)).isNull();
        assertThat(DmCursor.decode("")).isNull();
        assertThat(DmCursor.decode("   ")).isNull();
    }

    @Test
    @DisplayName("编码是不透明的 Base64 URL，不是明文十进制")
    void encodedValueIsOpaque() {
        String encoded = new DmCursor(42L).encode();

        assertThat(encoded).doesNotContain("42");
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII))
                .isEqualTo("42");
    }

    @Test
    @DisplayName("乱码被拒")
    void rejectsGarbage() {
        expectRejected("!!!not-base64!!!");
    }

    @Test
    @DisplayName("非数字被拒")
    void rejectsNonNumeric() {
        expectRejected(encode("abc"));
    }

    @Test
    @DisplayName("非正数被拒")
    void rejectsNonPositive() {
        expectRejected(encode("0"));
        expectRejected(encode("-1"));
    }

    private static String encode(String raw) {
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static void expectRejected(String cursor) {
        assertThatThrownBy(() -> DmCursor.decode(cursor))
                .isInstanceOf(BizException.class)
                .satisfies(thrown -> assertThat(((BizException) thrown).getErrorCode())
                        .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode()));
    }
}
