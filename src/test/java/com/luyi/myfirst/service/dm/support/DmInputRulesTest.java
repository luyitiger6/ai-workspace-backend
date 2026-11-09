package com.luyi.myfirst.service.dm.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 私信正文校验（spec「文本消息校验」）。
 *
 * 拒绝：null、空串、纯空白、2001 字符；接受：2000 字符。
 * 长度按码点计，因此含 emoji 的 2000 码点应当通过——按 UTF-16 单元计会误判为 4000。
 */
class DmInputRulesTest {

    @Test
    @DisplayName("null 与空串被拒")
    void rejectsNullAndEmpty() {
        expectRejected(null);
        expectRejected("");
    }

    @Test
    @DisplayName("纯空白被拒（空格、换行、制表符）")
    void rejectsBlank() {
        expectRejected("   ");
        expectRejected("\n\n");
        expectRejected("\t \t");
    }

    @Test
    @DisplayName("2000 字符接受，2001 字符拒绝")
    void boundaryIsTwoThousandChars() {
        String exact = "a".repeat(DmInputRules.CONTENT_MAX_CHARS);
        assertThat(DmInputRules.requireContent(exact)).isSameAs(exact);

        expectRejected("a".repeat(DmInputRules.CONTENT_MAX_CHARS + 1));
    }

    @Test
    @DisplayName("含 emoji 的 2000 码点接受（不按 UTF-16 单元计长）")
    void countsCodePointsNotUtf16Units() {
        String emojis = "😀".repeat(DmInputRules.CONTENT_MAX_CHARS);
        assertThat(emojis.length()).isEqualTo(DmInputRules.CONTENT_MAX_CHARS * 2);

        assertThat(DmInputRules.requireContent(emojis)).isSameAs(emojis);
        expectRejected(emojis + "a");
    }

    @Test
    @DisplayName("原文返回，不 trim")
    void keepsRawText() {
        String raw = "  你好  ";
        assertThat(DmInputRules.requireContent(raw)).isEqualTo("  你好  ");
    }

    private static void expectRejected(String raw) {
        assertThatThrownBy(() -> DmInputRules.requireContent(raw))
                .isInstanceOf(BizException.class)
                .satisfies(thrown -> assertThat(((BizException) thrown).getErrorCode())
                        .isEqualTo(AuthErrorEnum.VALIDATION_FAILED.getErrorCode()));
    }
}
