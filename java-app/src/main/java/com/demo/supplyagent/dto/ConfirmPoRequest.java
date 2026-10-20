package com.demo.supplyagent.dto;

import lombok.Data;

/**
 * confirm-po 请求：对一张 pending_confirmation 采购单做人工确认。
 */
@Data
public class ConfirmPoRequest {

    private String poNo;
}
