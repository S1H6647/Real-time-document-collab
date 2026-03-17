package com.project.realtimedoccollab.auth.dto;


import com.project.realtimedoccollab.user.User;

public record LoginResponse(
        String name,
        String email,
        String token
) {
    public static LoginResponse from(User user, String token) {
        return new LoginResponse(
                user.getName(),
                user.getEmail(),
                token
        );
    }
}

