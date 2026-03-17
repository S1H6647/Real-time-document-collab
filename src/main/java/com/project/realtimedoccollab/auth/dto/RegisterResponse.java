package com.project.realtimedoccollab.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record RegisterResponse(
        UUID id,
        String name,
        String email,
        Instant createdAt
) {
}

