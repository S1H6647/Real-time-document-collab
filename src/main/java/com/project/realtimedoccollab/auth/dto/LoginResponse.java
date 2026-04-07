package com.project.realtimedoccollab.auth.dto;


import com.project.realtimedoccollab.user.User;

import java.util.UUID;

public record LoginResponse(
        UUID id,
        String name,
        String email,
        String token
) {
    public static LoginResponse from(User user, String token) {
        return new LoginResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                token
        );
    }
}

