package com.dodaso.ecosystem.elcm.audit;

import com.dodaso.ecosystem.baseline.common.audit.SecurityContextOrHeaderAuditorAware;
import org.springframework.stereotype.Component;

/** Resolves created_by / updated_by from the security context or the X-User-Context header. */
@Component("headerBasedAuditorAware")
public class HeaderBasedAuditorAware extends SecurityContextOrHeaderAuditorAware {}
