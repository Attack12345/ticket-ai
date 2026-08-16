package com.ticketai.common;

import lombok.Data;

import java.util.List;

/**
 * 分页返回结构
 */
@Data
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setPages(size == 0 ? 0 : (total + size - 1) / size);
        return result;
    }
}
