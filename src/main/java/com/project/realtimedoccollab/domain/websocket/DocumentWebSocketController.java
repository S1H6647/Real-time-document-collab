package com.project.realtimedoccollab.domain.websocket;

import com.project.realtimedoccollab.domain.document.DocumentService;
import com.project.realtimedoccollab.domain.dto.websocket.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Objects;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class DocumentWebSocketController {

    private final DocumentService documentService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/document.join")
    public void handleJoin(
            @Payload DocumentJoinRequest request,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        UUID userId = extractUserId(headerAccessor);
        String email = extractEmail(headerAccessor);

        // Service handles access check + presence + notification
        documentService.joinDocument(userId, request.documentId());

        // Controller only handles broadcasting
        var activeEditors = documentService.getActiveEditors(request.documentId());
        PresenceEvent event = PresenceEvent.joined(
                request.documentId(), userId, email, activeEditors);

        messagingTemplate.convertAndSend(
                "/topic/document." + request.documentId() + ".presence",
                event
        );
    }

    @MessageMapping("/document.edit")
    public void handleEdit(
            @Payload DocumentEditRequest request,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        UUID userId = extractUserId(headerAccessor);
        String email = extractEmail(headerAccessor);

        log.debug("Edit received from user {} on document {}", email, request.documentId());

        // saves the edit to DB - returns the saved content
        documentService.applyEdit(userId, request.documentId(), request.content());

        // Broadcast the edit to all editors subscribed to this document
        DocumentEditEvent event = DocumentEditEvent.from(request, userId, email);

        messagingTemplate.convertAndSend(
                "/topic/document." + request.documentId() + ".edits",
                event
        );
    }

    @MessageMapping("/document.leave")
    public void handleLeave(
            @Payload DocumentLeaveRequest request,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID userId = extractUserId(headerAccessor);
        String email = extractEmail(headerAccessor);

        log.info("User {} leaving document {}", email, request.documentId());

        // Removes user from Redis presence set
        documentService.leaveDocument(userId, request.documentId());

        // Broadcast to remaining editors that this user left
        var activeEditors = documentService.getActiveEditors(request.documentId());
        PresenceEvent event = PresenceEvent.left(
                request.documentId(), userId, email, activeEditors);

        messagingTemplate.convertAndSend(
                "/topic/document." + request.documentId() + ".presence",
                event
        );
    }

    private UUID extractUserId(SimpMessageHeaderAccessor headerAccessor) {
        UUID userId = (UUID) Objects.requireNonNull(headerAccessor.getSessionAttributes()).get("userId");
        if (userId == null) {
            throw new IllegalStateException("No userId in WebSocket session — handshake may have failed");
        }
        return userId;
    }

    private String extractEmail(SimpMessageHeaderAccessor headerAccessor) {
        String email = (String) Objects.requireNonNull(headerAccessor.getSessionAttributes()).get("email");
        if (email == null) {
            throw new IllegalStateException("No email in WebSocket session — handshake may have failed");
        }
        return email;
    }
}
