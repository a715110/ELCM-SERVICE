package com.dodaso.ecosystem.elcm.audit;

import com.dodaso.ecosystem.baseline.common.audit.UtcLocalDateTimeProvider;
import org.springframework.stereotype.Component;

/** Supplies the audit timestamp in UTC. */
@Component("auditDateTimeAware")
public class AuditDateTimeAware extends UtcLocalDateTimeProvider {}
