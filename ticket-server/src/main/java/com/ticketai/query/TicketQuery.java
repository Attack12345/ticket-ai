package com.ticketai.query;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单分页查询条件（DEV_DOC §6.3）
 */
@Data
public class TicketQuery {

    /** 单页上限：防 size=1000000 全表拉取拖垮 DB（DEV_DOC 文档 P1-6） */
    public static final int MAX_PAGE_SIZE = 100;

    private Integer page = 1;
    private Integer size = 20;

    private Integer status;
    private Integer priority;
    private String category;
    private Long agentId;
    /** 模糊搜索：标题/描述 */
    private String keyword;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 排序：createTime/updateTime，格式 field:asc|desc，默认 createTime:desc */
    private String sort = "createTime:desc";

    public void setPage(Integer page) {
        this.page = (page == null || page < 1) ? 1 : page;
    }

    public void setSize(Integer size) {
        this.size = (size == null || size < 1) ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }

    public <T> Page<T> toPage() {
        return new Page<>(page, size);
    }
}
