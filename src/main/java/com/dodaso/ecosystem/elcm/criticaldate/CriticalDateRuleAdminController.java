package com.dodaso.ecosystem.elcm.criticaldate;

import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.CheckResult;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.EnabledRequest;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.RuleAdminView;
import com.dodaso.ecosystem.elcm.criticaldate.CriticalDateRuleDtos.SaveRuleRequest;
import com.dodaso.ecosystem.elcm.security.CallerIdentityResolver;
import com.dodaso.ecosystem.elcm.security.ElcmPermission;
import com.dodaso.ecosystem.elcm.security.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADDED 2026-11-21 -- backs the Critical Date Rules admin screen. Every call needs the
 * critical-date admin permission (System Admin and Super Admin). Each write answers with the whole
 * screen as it stands afterwards. Deletes are POSTs so every call works through the shared REST
 * client.
 */
@RestController
@RequestMapping("/api/v1/pipeline/critical-dates/rules")
@RequiredArgsConstructor
public class CriticalDateRuleAdminController {

  private final CriticalDateRuleAdminService service;
  private final CallerIdentityResolver callerIdentityResolver;

  @GetMapping
  @RequiresPermission(ElcmPermission.CRITICAL_DATE_ADMIN)
  public RuleAdminView view() {
    return service.view();
  }

  @PostMapping
  @RequiresPermission(ElcmPermission.CRITICAL_DATE_ADMIN)
  public RuleAdminView create(@RequestBody final SaveRuleRequest body,
      final HttpServletRequest request) {
    service.create(body, callerIdentityResolver.loginIdOf(request));
    return service.view();
  }

  @PutMapping("{id}")
  @RequiresPermission(ElcmPermission.CRITICAL_DATE_ADMIN)
  public RuleAdminView update(@PathVariable final Long id, @RequestBody final SaveRuleRequest body,
      final HttpServletRequest request) {
    service.update(id, body, callerIdentityResolver.loginIdOf(request));
    return service.view();
  }

  @PostMapping("{id}/enabled")
  @RequiresPermission(ElcmPermission.CRITICAL_DATE_ADMIN)
  public RuleAdminView setEnabled(@PathVariable final Long id,
      @RequestBody final EnabledRequest body, final HttpServletRequest request) {
    service.setEnabled(id, body != null && body.enabled(),
        callerIdentityResolver.loginIdOf(request));
    return service.view();
  }

  @PostMapping("{id}/delete")
  @RequiresPermission(ElcmPermission.CRITICAL_DATE_ADMIN)
  public RuleAdminView delete(@PathVariable final Long id, final HttpServletRequest request) {
    service.delete(id, callerIdentityResolver.loginIdOf(request));
    return service.view();
  }

  @PostMapping("check")
  @RequiresPermission(ElcmPermission.CRITICAL_DATE_ADMIN)
  public CheckResult checkNow(final HttpServletRequest request) {
    return service.checkNow(callerIdentityResolver.loginIdOf(request));
  }
}
