package com.ticketai.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分段器测试（DEV_DOC §5.4.2：标题层级分段 + 500 字滑动窗口重叠 50 字）。
 */
class KnowledgeSegmenterTest {

    private final KnowledgeSegmenter segmenter = new KnowledgeSegmenter();

    @Test
    @DisplayName("按标题分段：每个标题下的内容独立成段")
    void splitByTitle() {
        String content = "# 退款政策\n七天无理由退货\n# 换货流程\n联系客服提交申请";
        List<String> parts = segmenter.segment(content);

        assertEquals(2, parts.size());
        assertTrue(parts.get(0).startsWith("退款政策"));
        assertTrue(parts.get(1).startsWith("换货流程"));
    }

    @Test
    @DisplayName("长文本滑动窗口：超过 500 字按窗口切分并带重叠")
    void slidingWindow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("这是一段用于测试分段器的填充文本内容。");
        }
        String content = sb.toString();  // 60 * 17 = 1020 字
        List<String> parts = segmenter.segment(content);

        assertTrue(parts.size() >= 2, "长文本应切分为多段");
        for (String part : parts) {
            assertTrue(part.length() <= 500, "每段不超过 500 字");
        }
        // 相邻段应有重叠（滑动窗口）
        assertEquals(parts.get(0).substring(parts.get(0).length() - 50),
                parts.get(1).substring(0, 50), "相邻段应有 50 字重叠");
    }

    @Test
    @DisplayName("短文本不切分")
    void shortTextNoSplit() {
        List<String> parts = segmenter.segment("一句话");
        assertEquals(1, parts.size());
    }

    @Test
    @DisplayName("空内容返回空列表")
    void emptyContent() {
        assertTrue(segmenter.segment("").isEmpty());
        assertTrue(segmenter.segment("   \n  ").isEmpty());
    }
}
