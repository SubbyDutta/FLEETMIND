package com;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationDedupe dedupe;
    private final NotificationPreferenceRepository preferences;
    private final NotificationRepository notifications;
    private final EmailSender emailSender;

    @Transactional
    public void notify(String eventId, String tenant, String eventType,
                       String subject, String summary, String template, Map<String, Object> model) {

        if (!dedupe.markIfNew(eventId)) {
            log.debug("skipping already-processed event {}", eventId);
            return;
        }

        List<NotificationPreference> recipients = preferences.findRecipients(tenant, eventType);
        if (recipients.isEmpty()) {
            log.debug("no recipients for tenant={} eventType={}", tenant, eventType);
            return;
        }

        for (NotificationPreference pref : recipients) {
            if (pref.inappEnabled()) {
                notifications.insert(tenant, pref.email(), eventType, subject, summary);
            }
            if (pref.emailEnabled()) {
                emailSender.send(pref.email(), subject, template, model);
            }
        }
        log.info("event {} → {} recipient(s) [{}]", eventId, recipients.size(), eventType);
    }
}
