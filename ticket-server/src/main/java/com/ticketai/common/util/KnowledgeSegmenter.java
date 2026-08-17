package com.ticketai.common.util;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库分段器（DEV_DOC §5.4.2）：
 * 按 Markdown 标题层级分段；无标题段落按 500 字定长滑动窗口切分（重叠 50 字）。
 */
@Component
public class KnowledgeSegmenter {

    private static final Pattern TITLE = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final int MAX_LEN = 500;
    private static final int OVERLAP = 50;

    public List<String> segment(String content) {
        List<String> parts = new ArrayList<>();
        // 按标题切块
        Matcher matcher = TITLE.matcher(content);
        int lastTitleEnd = -1;
        String lastTitle = "";
        while (matcher.find()) {
            if (lastTitleEnd >= 0) {
                String block = content.substring(lastTitleEnd, matcher.start());
                parts.addAll(splitBlock(combine(lastTitle, block)));
            }
            lastTitle = matcher.group(2);
            lastTitleEnd = matcher.end();
        }
        if (lastTitleEnd >= 0) {
            parts.addAll(splitBlock(combine(lastTitle, content.substring(lastTitleEnd))));
        } else {
            // 无标题：整篇按滑动窗口切分
            parts.addAll(splitBlock(content));
        }
        return parts.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    private String combine(String title, String block) {
        return (title == null || title.isBlank() ? "" : title + "\n") + block;
    }

    /** 超过 MAX_LEN 按滑动窗口切分，否则原样返回 */
    private List<String> splitBlock(String block) {
        if (block.length() <= MAX_LEN) {
            return List.of(block);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < block.length()) {
            int end = Math.min(start + MAX_LEN, block.length());
            chunks.add(block.substring(start, end));
            if (end == block.length()) {
                break;
            }
            start = end - OVERLAP;
        }
        return chunks;
    }
}
