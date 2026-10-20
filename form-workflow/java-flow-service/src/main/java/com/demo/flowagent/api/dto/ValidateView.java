package com.demo.flowagent.api.dto;

import java.util.List;

public record ValidateView(boolean allowed, List<String> reasons, Integer expectedRecordVersion) {
}
