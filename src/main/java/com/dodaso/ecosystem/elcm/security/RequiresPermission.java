package com.dodaso.ecosystem.elcm.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission a controller method needs. Checked by {@link PermissionInterceptor}
 * on the server, independent of what the UI shows. Every new endpoint should carry one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {
  ElcmPermission value();
}
