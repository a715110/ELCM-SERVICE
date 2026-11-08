package com.dodaso.ecosystem.elcm.integration.ecws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** ADDED 2026-11-06. The package assignee gets the review task when they qualify. */
class EcwsAssigneePickerPreferredTest {

  private AssigneeDirectoryLookupService directory;
  private EcwsTaskOutboxRepository outbox;
  private EcwsAssigneePicker picker;

  private static UserDirectoryDTO user(final String login) {
    final UserDirectoryDTO dto = mock(UserDirectoryDTO.class);
    when(dto.getLoginId()).thenReturn(login);
    return dto;
  }

  @BeforeEach
  void setUp() {
    directory = mock(AssigneeDirectoryLookupService.class);
    outbox = mock(EcwsTaskOutboxRepository.class);
    picker = new EcwsAssigneePicker(directory, outbox);
    ReflectionTestUtils.setField(picker, "separationOn", true);
    when(directory.findAllWithRole("Reviewer"))
        .thenReturn(List.of(user("byoung"), user("kevin"), user("susan")));
    // byoung has no open tasks, kevin has three: the plain pick would choose byoung
    when(outbox.countByActionAndTaskOpenTrueAndAssigneeLoginIgnoreCase(any(), eq("kevin")))
        .thenReturn(3L);
  }

  @Test
  void packageAssigneeWinsOverTheLeastBusyReviewer() {
    assertThat(picker.pickPreferring("Reviewer", "Kevin", List.of("susan"))).contains("kevin");
  }

  @Test
  void assigneeWhoIsTheSubmitterIsSkipped() {
    assertThat(picker.pickPreferring("Reviewer", "susan", List.of("susan"))).contains("byoung");
  }

  @Test
  void assigneeWithoutTheRoleFallsBackToLeastBusy() {
    assertThat(picker.pickPreferring("Reviewer", "tom", List.of("susan"))).contains("byoung");
  }

  @Test
  void noAssigneeFallsBackToLeastBusy() {
    assertThat(picker.pickPreferring("Reviewer", null, List.of("susan"))).contains("byoung");
  }
}
