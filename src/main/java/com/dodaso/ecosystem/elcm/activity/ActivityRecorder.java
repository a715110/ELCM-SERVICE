package com.dodaso.ecosystem.elcm.activity;

import com.dodaso.ecosystem.baseline.common.constant.ServiceDiscoveryEnum;
import com.dodaso.ecosystem.baseline.common.proxy.RESTServiceClient;
import com.dodaso.ecosystem.ears.constants.EventKind;
import com.dodaso.ecosystem.ears.constants.SourceTriggerType;
import com.dodaso.ecosystem.ears.container.EventReminderRegisterDTOContainer;
import com.dodaso.ecosystem.ears.dto.EventReminderRegisterDTO;
import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ADDED 2026-10-20 -- writes one Activity Log line for a contract package to EARS.
 *
 * <p>Best effort, on purpose: the line is sent on a background thread after the user's action has
 * already been committed, so a slow or unavailable EARS never slows down, blocks or undoes the
 * action. A failure is logged as a warning (ACTIVITY-LOG) and the line is lost. The caller's login
 * id and the time are taken when the method is called, not when the line is sent.
 *
 * <p>Callers use the public face of a service, after its database work returned, so a line is only
 * written for something that really happened.
 */
@Slf4j
@Component
public class ActivityRecorder {

  /** The EARS source table name of every package line. */
  public static final String TABLE = "contract_package";

  static final String SYSTEM_ACTOR = "system";
  private static final String ENDPOINT = "/eventReminderController/fireEventReminder";
  private static final int MAX_SUBJECT = 100;
  private static final int MAX_BODY = 500;
  private static final int QUEUE_SIZE = 2000;

  private final RESTServiceClient restServiceClient;
  private final CallerIdentityResolver callerIdentityResolver;

  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(QUEUE_SIZE), runnable -> {
        final Thread thread = new Thread(runnable, "activity-log");
        thread.setDaemon(true);
        return thread;
      });

  public ActivityRecorder(final RESTServiceClient restServiceClient,
      final CallerIdentityResolver callerIdentityResolver) {
    this.restServiceClient = restServiceClient;
    this.callerIdentityResolver = callerIdentityResolver;
  }

  /** A line for something a person did; the person is the caller of the current request. */
  public void record(final Long packageId, final ActivityEvent event, final String message) {
    send(packageId, event, currentActor(), SourceTriggerType.ELCM_UI, () -> message);
  }

  /** Same, with the person named by the caller. */
  public void record(final Long packageId, final ActivityEvent event, final String actor,
      final String message) {
    send(packageId, event, blankToSystem(actor), SourceTriggerType.ELCM_UI, () -> message);
  }

  /**
   * Same, but the text is built later on the background thread. Use it when the text needs a lookup
   * (a file name, say) that should not slow down the user.
   */
  public void recordLater(final Long packageId, final ActivityEvent event,
      final Supplier<String> message) {
    send(packageId, event, currentActor(), SourceTriggerType.ELCM_UI, message);
  }

  /** A line for something the system did on its own (the extraction worker). */
  public void recordSystem(final Long packageId, final ActivityEvent event, final String message) {
    send(packageId, event, SYSTEM_ACTOR, SourceTriggerType.ELCM_SYSGEN, () -> message);
  }

  private void send(final Long packageId, final ActivityEvent event, final String actor,
      final SourceTriggerType source, final Supplier<String> message) {
    if (packageId == null || event == null) {
      return;
    }
    final Instant occurredAt = Instant.now();
    try {
      executor.execute(() -> deliver(packageId, event, actor, source, occurredAt, message));
    } catch (final RejectedExecutionException e) {
      log.warn("ACTIVITY-LOG queue is full, dropped {} for package {}", event, packageId);
    }
  }

  private void deliver(final Long packageId, final ActivityEvent event, final String actor,
      final SourceTriggerType source, final Instant occurredAt, final Supplier<String> message) {
    try {
      final int id = Math.toIntExact(packageId);
      String text;
      try {
        text = message.get();
      } catch (final RuntimeException e) {
        log.debug("ACTIVITY-LOG could not build the text of {} for package {}", event, packageId, e);
        text = null;
      }
      final EventReminderRegisterDTO dto = EventReminderRegisterDTO.builder()
          .templateName(event.getTemplateName())
          .sourceTriggered(source)
          .sourceReferenceId(id)
          .sourceReferenceTable(TABLE)
          .uiComponentType("package")
          .priority("Low")
          .subject(cut(event.getLabel(), MAX_SUBJECT))
          .body(cut(text != null && !text.isBlank() ? text : event.getLabel() + ".", MAX_BODY))
          .actor(actor)
          .occurredAt(occurredAt)
          .kind(EventKind.ACTIVITY)
          .build();
      final EventReminderRegisterDTOContainer request = new EventReminderRegisterDTOContainer();
      request.setEventReminderRegisterDTO(dto);
      postWithOneRetry(request);
    } catch (final Exception e) {
      log.warn("ACTIVITY-LOG could not record {} for package {}: {}", event, packageId,
          e.getMessage());
    }
  }

  private void postWithOneRetry(final EventReminderRegisterDTOContainer request) throws Exception {
    try {
      post(request);
    } catch (final Exception first) {
      Thread.sleep(1500L);
      post(request);
    }
  }

  private void post(final EventReminderRegisterDTOContainer request) throws Exception {
    restServiceClient.post(ServiceDiscoveryEnum.ears_service.getServiceDiscoveryName(), ENDPOINT,
        request, new ParameterizedTypeReference<EventReminderRegisterDTOContainer>() {
        });
  }

  private String currentActor() {
    try {
      if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
        final HttpServletRequest request = attrs.getRequest();
        return blankToSystem(callerIdentityResolver.loginIdOf(request));
      }
    } catch (final RuntimeException e) {
      log.debug("ACTIVITY-LOG could not resolve the caller", e);
    }
    return SYSTEM_ACTOR;
  }

  private static String blankToSystem(final String actor) {
    return actor == null || actor.isBlank() ? SYSTEM_ACTOR : cut(actor.trim(), 100);
  }

  static String cut(final String text, final int max) {
    if (text == null || text.length() <= max) {
      return text;
    }
    return text.substring(0, Math.max(0, max - 3)) + "...";
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
