package com.project.realtimedoccollab.user;

import com.project.realtimedoccollab.auth.dto.LoginRequest;
import com.project.realtimedoccollab.auth.dto.LoginResponse;
import com.project.realtimedoccollab.auth.dto.RegisterRequest;
import com.project.realtimedoccollab.auth.dto.RegisterResponse;
import com.project.realtimedoccollab.auth.jwt.JwtUtil;
import com.project.realtimedoccollab.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    /**
     * Register a new user account.
     * Throws {@link IllegalArgumentException} if the email is already taken.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already in use");
        }

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();

        User saved = userRepository.save(user);

        return new RegisterResponse(
                saved.getId(),
                saved.getName(),
                saved.getEmail(),
                saved.getCreatedAt()
        );
    }

    /**
     * Authenticate a user and return their details with a  signed JWT token.
     * Throws {@link org.springframework.security.authentication.BadCredentialsException}
     * if credentials are invalid.
     */
    public LoginResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = userRepository.findUserByEmail(request.email())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return LoginResponse.from(user, jwtUtil.generateToken(user));
    }
}
