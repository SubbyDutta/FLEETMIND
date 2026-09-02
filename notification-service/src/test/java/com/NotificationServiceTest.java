package com;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationDedupe dedupe;
    @Mock NotificationPreferenceRepository preferences;
    @Mock NotificationRepository notifications;
    @Mock EmailSender emailSender;

    @InjectMocks NotificationService service;

    private final Map<String, Object> model = Map.of("reason", "late");

    @BeforeEach
    void freshEventByDefault() {
        when(dedupe.markIfNew(anyString())).thenReturn(true);
    }

    @Test
    void duplicateEventSendsNothing() {
        when(dedupe.markIfNew("alerts-0-42")).thenReturn(false);

        service.notify("alerts-0-42", "acme", "ALERT", "subj", "sum", "alert", model);

        verifyNoInteractions(preferences, notifications, emailSender);
    }

    @Test
    void bothChannelsEnabledSendsEmailAndInApp() {
        when(preferences.findRecipients("acme", "ALERT"))
                .thenReturn(List.of(new NotificationPreference("dispatcher@acme.com", true, true)));

        service.notify("alerts-0-1", "acme", "ALERT", "subj", "sum", "alert", model);

        verify(notifications).insert("acme", "dispatcher@acme.com", "ALERT", "subj", "sum");
        verify(emailSender).send("dispatcher@acme.com", "subj", "alert", model);
    }

    @Test
    void emailOnlyPreferenceSkipsInAppRow() {
        when(preferences.findRecipients("acme", "ALERT"))
                .thenReturn(List.of(new NotificationPreference("admin@acme.com", true, false)));

        service.notify("alerts-0-2", "acme", "ALERT", "subj", "sum", "alert", model);

        verify(emailSender).send("admin@acme.com", "subj", "alert", model);
        verify(notifications, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void inAppOnlyPreferenceSkipsEmail(){
        when(preferences.findRecipients("acme", "ALERT"))
                .thenReturn(List.of(new NotificationPreference("viewer@acme.com", false, true)));

        service.notify("alerts-0-3", "acme", "ALERT", "subj", "sum", "alert", model);

        verify(notifications).insert("acme", "viewer@acme.com", "ALERT", "subj", "sum");
        verify(emailSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void mailFailurePropagatesSoTheErrorHandlerRetries() {
        when(preferences.findRecipients("acme", "ALERT"))
                .thenReturn(List.of(new NotificationPreference("dispatcher@acme.com", true, true)));
        doThrow(new MailSendException("smtp down"))
                .when(emailSender).send(eq("dispatcher@acme.com"), any(), any(), any());

        assertThatThrownBy(() ->
                service.notify("alerts-0-4", "acme", "ALERT", "subj", "sum", "alert", model))
                .isInstanceOf(MailSendException.class);
    }
}
