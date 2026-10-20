package com.demo.supplyagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * W4-1 会话历史查询结果：items（当前页，时间正序）+ total（本人该会话全量行数）+ page/size 回显。
 */
@Data
@AllArgsConstructor
public class ChatHistoryData {

    private String conversationId;

    private List<ChatHistoryItem> items;

    private long total;

    private int page;

    private int size;
}
