package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.security.SecretCipher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encrypted-at-rest form of the only message content Robin keeps: the capped
 * subject, plain text and links needed to find a verification code or link.
 * AES-256-GCM through {@link SecretCipher} (APP_SECRETS_KEY); every value has a
 * fresh IV. Decrypted content stays in memory for the extraction and is never
 * logged or returned by an API.
 */
@Component
public class ManagedMessageContent {

    public record Content(String subject, String text, List<String> links) {
        public Content {
            subject = subject == null ? "" : subject;
            text = text == null ? "" : text;
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    private final SecretCipher cipher;
    private final ObjectMapper json;

    public ManagedMessageContent(SecretCipher cipher, ObjectMapper json) {
        this.cipher = cipher;
        this.json = json;
    }

    public String encrypt(InboundMailMessage message) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("subject", message.subject());
        content.put("text", message.text());
        content.put("links", message.links());
        try {
            return cipher.encrypt(json.writeValueAsString(content));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Inbound message content could not be serialised", e);
        }
    }

    public Content decrypt(String encrypted) {
        try {
            Map<?, ?> map = json.readValue(cipher.decrypt(encrypted), Map.class);
            List<String> links = new ArrayList<>();
            if (map.get("links") instanceof List<?> list) {
                for (Object link : list) if (link instanceof String s) links.add(s);
            }
            Object subject = map.get("subject");
            Object text = map.get("text");
            return new Content(subject instanceof String s ? s : "", text instanceof String t ? t : "", links);
        } catch (RuntimeException | JsonProcessingException e) {
            // Deliberately no cause message: it could quote content.
            throw new IllegalStateException("Stored managed message content could not be read");
        }
    }
}
