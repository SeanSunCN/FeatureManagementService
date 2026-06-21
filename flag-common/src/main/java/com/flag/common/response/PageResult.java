package com.flag.common.response;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class PageResult<T> {
    private long total;
    private int page;
    private int size;
    private List<T> records;

    public static <T> PageResult<T> of(long total, int page, int size, List<T> records) {
        PageResult<T> pr = new PageResult<>();
        pr.total = total;
        pr.page = page;
        pr.size = size;
        pr.records = records != null ? records : Collections.emptyList();
        return pr;
    }

    public static <T> PageResult<T> empty() {
        return of(0, 1, 10, Collections.emptyList());
    }
}