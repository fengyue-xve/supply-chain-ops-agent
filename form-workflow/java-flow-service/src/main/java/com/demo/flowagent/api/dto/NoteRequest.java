package com.demo.flowagent.api.dto;

import jakarta.validation.constraints.Size;

public record NoteRequest(
        @Size(max = 200) String note) {
}
