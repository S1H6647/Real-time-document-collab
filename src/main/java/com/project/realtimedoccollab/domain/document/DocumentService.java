package com.project.realtimedoccollab.domain.document;

import com.project.realtimedoccollab.auth.user.UserPrincipal;
import com.project.realtimedoccollab.config.RedisKeys;
import com.project.realtimedoccollab.domain.collaborator.CollaboratorRole;
import com.project.realtimedoccollab.domain.collaborator.DocumentCollaborator;
import com.project.realtimedoccollab.domain.collaborator.DocumentCollaboratorRepository;
import com.project.realtimedoccollab.domain.dto.*;
import com.project.realtimedoccollab.domain.edit.DocumentEdit;
import com.project.realtimedoccollab.domain.edit.DocumentEditRepository;
import com.project.realtimedoccollab.domain.edit.DocumentEditService;
import com.project.realtimedoccollab.domain.redis.RedisDocumentPublisher;
import com.project.realtimedoccollab.exception.ResourceNotFoundException;
import com.project.realtimedoccollab.notification.NotificationService;
import com.project.realtimedoccollab.notification.NotificationType;
import com.project.realtimedoccollab.user.User;
import com.project.realtimedoccollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentEditRepository documentEditRepository;
    private final DocumentCollaboratorRepository collaboratorRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final UserRepository userRepository;
    private final RedisDocumentPublisher publisher;
    private final DocumentEditService documentEditService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public List<DocumentSummaryResponse> getDocumentsByOwner(User owner) {
        // Get owned documents
        List<Document> ownedDocs = documentRepository.findByOwnerOrderByUpdatedAtDesc(owner);

        // Get documents shared with this user
        List<Document> sharedDocs = documentRepository.findByCollaboratorsUserId(owner.getId());

        // Combine and deduplicate
        Map<Long, Document> allDocs = new LinkedHashMap<>();
        ownedDocs.forEach(doc -> allDocs.put(doc.getId(), doc));
        sharedDocs.forEach(doc -> allDocs.putIfAbsent(doc.getId(), doc));

        return allDocs.values().stream()
                .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                .map(doc -> new DocumentSummaryResponse(
                        doc.getId(),
                        doc.getTitle(),
                        doc.getOwner().getName(),
                        doc.getUpdatedAt()
                ))
                .toList();
    }

    @Transactional
    public DocumentResponse createDocument(CreateDocumentRequest request, User owner) {
        Document document = Document.builder()
                .title(request.title())
                .content(request.content())
                .owner(owner)
                .build();

        Document savedDocument = documentRepository.save(document);

        return DocumentResponse.from(savedDocument);
    }

    @Transactional
    public DocumentSummaryResponse updateTitle(
            Long id,
            UpdateTitleRequest request,
            UserPrincipal userPrincipal
    ) {
        User user = userPrincipal.getUser();

        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found!"));

        if (!document.getOwner().getId().equals(user.getId())) {
            throw new AccessDeniedException("You don't have permission to edit this document!");
        }

        List<DocumentCollaborator> collaboratorList = document.getCollaborators();

        if (collaboratorList.stream()
                .anyMatch(c ->
                        !CollaboratorRole.EDITOR.equals(c.getRole()))) {
            throw new AccessDeniedException("You don't have permission to edit this document!");
        }

        document.setTitle(request.title());
        document.setOwner(document.getOwner());

        return DocumentSummaryResponse.from(document);
    }

    @Transactional
    public DocumentResponse editDocumentById(Long id, UpdateDocumentRequest request, UserPrincipal userPrincipal) {
        User user = userPrincipal.getUser();

        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found!"));

        boolean isOwner = document.getOwner().getId().equals(user.getId());
        boolean isEditorCollaborator = document.getCollaborators().stream()
                .anyMatch(c -> c.getUser().getId().equals(user.getId())
                        && CollaboratorRole.EDITOR.equals(c.getRole()));

        if (!isOwner && !isEditorCollaborator) {
            throw new AccessDeniedException("You don't have permission to edit this document!");
        }

        if (request.content() != null) {
            document.setContent(request.content());
        }

        DocumentEdit editEntry = DocumentEdit.builder()
                .document(document)
                .editor(user)
                .contentSnapshot(document.getContent())
                .build();

        documentEditRepository.save(editEntry);

        return DocumentResponse.from(document);
    }

    @Transactional
    public DocumentResponse saveDocument(Long id, SaveDocumentRequest request, UserPrincipal userPrincipal) {
        User user = userPrincipal.getUser();

        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found!"));

        boolean isOwner = document.getOwner().getId().equals(user.getId());
        boolean isEditorCollaborator = document.getCollaborators().stream()
                .anyMatch(c -> c.getUser().getId().equals(user.getId())
                        && CollaboratorRole.EDITOR.equals(c.getRole()));

        if (!isOwner && !isEditorCollaborator) {
            throw new AccessDeniedException("You don't have permission to edit this document!");
        }

        String previousContent = document.getContent();

        if (request.title() != null) {
            document.setTitle(request.title());
        }
        if (request.content() != null) {
            document.setContent(request.content());
        }

        DocumentEdit editEntry = DocumentEdit.builder()
                .document(document)
                .editor(user)
                .contentSnapshot(document.getContent())
                .build();

        documentEditRepository.save(editEntry);
        Document saved = documentRepository.save(document);

        return DocumentResponse.from(saved);
    }

    @Transactional
    public void deleteDocument(Long id, UserPrincipal userPrincipal) {
        User user = userPrincipal.getUser();

        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found!"));

        if (!document.getOwner().getId().equals(user.getId())) {
            throw new AccessDeniedException("You don't have permission to delete this document!");
        }

        documentRepository.delete(document);
    }


    @Transactional(readOnly = true)
    public DocumentResponse getDocument(
            Long documentId,
            User user) {

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found!"));

        boolean isOwner = document.getOwner().getId().equals(user.getId());

        boolean isCollaborator = document.getCollaborators().stream()
                .anyMatch(c -> c.getUser().getId().equals(user.getId()));

        if (!isOwner && !isCollaborator) {
            throw new AccessDeniedException("You don't have permission to view this document!");
        }

        return DocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public EditHistoryResponse getEditHistory(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found!"));

        List<DocumentEdit> editHistory = document.getEditHistory().stream()
                .sorted(Comparator.comparing(DocumentEdit::getEditedAt))
                .toList();

        List<DocumentEditEntry> entryList = new ArrayList<>();
        String previousContent = null;

        for (DocumentEdit edit : editHistory) {
            String summary = generateSummary(previousContent, edit.getContentSnapshot());
            entryList.add(DocumentEditEntry.from(edit, summary));
            previousContent = edit.getContentSnapshot();
        }

        return EditHistoryResponse.of(document.getId(), entryList);
    }

    private String generateSummary(String previousContent, String newContent) {
        if (previousContent == null) {
            return "Created document";
        }

        int previousLength = previousContent.length();
        int newLength = newContent.length();
        int diff = newLength - previousLength;

        if (diff > 100) {
            return "Added content (" + diff + " characters)";
        } else if (diff < -100) {
            return "Removed content (" + Math.abs(diff) + " characters)";
        } else {
            return "Minor edit";
        }
    }

    @Transactional(readOnly = true)
    public void joinDocument(UUID userId, long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        User joiner = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean isOwner = document.getOwner().getId().equals(userId);
        boolean isCollaborator = collaboratorRepository
                .existsByUserIdAndDocument(userId, document);

        if (!isOwner && !isCollaborator) {
            throw new AccessDeniedException("No access to this document");
        }

        String presenceKey = RedisKeys.presenceKey(documentId);
        redisTemplate.opsForSet().add(presenceKey, joiner.getEmail());
        redisTemplate.expire(presenceKey, Duration.ofSeconds(300));

        if (!isOwner) {
            notificationService.sendNotificationAsync(
                    document.getOwner(),
                    document.getOwner().getEmail(),
                    NotificationType.COLLABORATOR_JOINED,
                    joiner.getEmail() + " joined \"" + document.getTitle() + "\"",
                    documentId
            );
        }
    }

    @Transactional
    public void applyEdit(UUID userId, long documentId, String content) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));

        User editor = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found!"));


        verifyCollaborator(userId, document);

        // Save to DB
        document.setContent(content);
        document.setUpdatedAt(Instant.now());
        documentRepository.save(document);

        // Persist audit history asynchronously — nobody is waiting for this
        documentEditService.persistEditAsync(document, editor, content);

        // Every edit proves the user is alive
        String key = RedisKeys.presenceKey(documentId);
        redisTemplate.expire(key, Duration.ofSeconds(300));

        // Publish to Redis so ALL server instances broadcast this edit
        publisher.publishEditEvent(userId, documentId, content);

        // Notify the document owner if the editor is not the owner
        if (!document.getOwner().getId().equals(userId)) {
            String throttleKey = RedisKeys.notificationThrottleKey(documentId, userId);

            // Only notify once every 30 minutes per editor to avoid spam
            Boolean shouldNotify = redisTemplate.opsForValue()
                    .setIfAbsent(throttleKey, "1", Duration.ofMinutes(30));

            if (Boolean.TRUE.equals(shouldNotify)) {
                notificationService.sendNotificationAsync(
                        document.getOwner(),
                        document.getOwner().getEmail(),
                        NotificationType.DOCUMENT_EDITED,
                        editor.getEmail() + " edited \"" + document.getTitle() + "\"",
                        documentId
                );
            }
        }

        log.debug("Edit applied by user {} on document {}", userId, documentId);
    }

    @Transactional(readOnly = true)
    public void leaveDocument(UUID userId, long documentId) {

        Document document = documentRepository.findById(documentId)
                .orElse(null);

        if (document == null) {
            log.warn("leaveDocument called for unknown document: {}", documentId);
            return;
        }

        User leaveUser = userRepository.findById(userId).orElse(null);
        if (leaveUser == null) return;

        boolean isOwner = document.getOwner().getId().equals(userId);
        boolean isCollaborator = collaboratorRepository.existsByUserIdAndDocument(userId, document);

        if (!isOwner && !isCollaborator) {
            throw new AccessDeniedException("No access to this document");
        }

        String key = RedisKeys.presenceKey(documentId);
        redisTemplate.opsForSet().remove(key, leaveUser.getEmail());

        if (!isOwner) {
            notificationService.sendNotificationAsync(
                    document.getOwner(),
                    document.getOwner().getEmail(),
                    NotificationType.COLLABORATOR_LEFT,
                    leaveUser.getEmail() + " left \"" + document.getTitle() + "\"",
                    documentId
            );
        }

        Long remaining = redisTemplate.opsForSet().size(key);
        if (remaining == null || remaining == 0) {
            redisTemplate.delete(key);
            log.info("User {} left document {}. No editors remain — presence key deleted.", leaveUser.getEmail(), documentId);
            return;
        }

        log.info("User {} left document {}. Remaining editors: {}",
                leaveUser.getEmail(), documentId, getActiveEditorCount(documentId));
    }

    // Presence queries
    public Set<String> getActiveEditors(long documentId) {
        String key = RedisKeys.presenceKey(documentId);
        Set<String> members = redisTemplate.opsForSet().members(key);
        return members != null ? members : Collections.emptySet();
    }

    public long getActiveEditorCount(long documentId) {
        String key = RedisKeys.presenceKey(documentId);
        Long count = redisTemplate.opsForSet().size(key);
        return count != null ? count : 0L;
    }

    // Helper
    private void verifyCollaborator(UUID userId, Document document) {
        boolean hasAccess = collaboratorRepository
                .existsByUserIdAndDocument(userId, document);

        if (!hasAccess) {
            throw new AccessDeniedException(
                    "User " + userId + " is not a collaborator on document " + document.getId()
            );
        }
    }
}
