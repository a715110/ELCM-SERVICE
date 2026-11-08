package com.dodaso.ecosystem.elcm.integration.ecws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.ContractPackage;
import com.dodaso.ecosystem.elcm.repository.pipeline.ContractPackageRepository;
import com.dodaso.ecosystem.elcm.repository.pipeline.SubmissionRepository;
import com.dodaso.ecosystem.elcm.service.pipeline.AssigneeDirectoryLookupService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** ADDED 2026-11-07. Handing the approval to another approver moves the open approval task. */
class EcwsTaskPublisherApproverChangedTest {

  private EcwsIntegrationProperties props;
  private EcwsTaskOutboxRepository outbox;
  private EcwsTaskPublisher publisher;
  private EcwsAssigneePicker picker;

  @BeforeEach
  void setUp() {
    props = new EcwsIntegrationProperties();
    props.setEnabled(true);
    outbox = mock(EcwsTaskOutboxRepository.class);
    final ContractPackageRepository packages = mock(ContractPackageRepository.class);
    final ContractPackage pkg = mock(ContractPackage.class);
    when(pkg.getPackageCode()).thenReturn("PKG-2026-001");
    when(packages.findById(5L)).thenReturn(Optional.of(pkg));
    final SubmissionRepository submissions = mock(SubmissionRepository.class);
    when(submissions.findByContractPackage_Id(5L)).thenReturn(List.of());
    final AssigneeDirectoryLookupService directory = mock(AssigneeDirectoryLookupService.class);
    when(directory.findByAssigneeId(any())).thenReturn(Optional.<UserDirectoryDTO>empty());
    picker = mock(EcwsAssigneePicker.class);
    publisher = new EcwsTaskPublisher(props, outbox, picker, packages,
        submissions, null, directory, null);
  }

  @Test
  void openApprovalTaskMovesAndAReassignRowIsQueued() {
    when(outbox.reassignOpen(5L, "APPROVAL", "alice")).thenReturn(1);
    publisher.approverChanged(5L, "alice", "kevin");
    final ArgumentCaptor<EcwsTaskOutbox> saved = ArgumentCaptor.forClass(EcwsTaskOutbox.class);
    verify(outbox).save(saved.capture());
    assertThat(saved.getValue().getAction()).isEqualTo(EcwsTaskOutbox.REASSIGN);
    assertThat(saved.getValue().getEventCode()).isEqualTo("APPROVAL");
    assertThat(saved.getValue().getAssigneeLogin()).isEqualTo("alice");
  }

  @Test
  void approverChosenInTheCompleteDialogGetsTheApprovalTask() {
    when(picker.pickPreferring(eq("Approver"), eq("alice"), any())).thenReturn(Optional.of("alice"));
    publisher.reviewCompleted(5L, "kevin", "alice");
    final ArgumentCaptor<EcwsTaskOutbox> saved = ArgumentCaptor.forClass(EcwsTaskOutbox.class);
    verify(outbox).save(saved.capture());
    assertThat(saved.getValue().getEventCode()).isEqualTo("APPROVAL");
    assertThat(saved.getValue().getAssigneeLogin()).isEqualTo("alice");
    assertThat(saved.getValue().getMemberLogins()).contains("kevin").contains("alice");
  }

  @Test
  void withoutAChoiceElcmPicksAsBefore() {
    when(picker.pickPreferring(eq("Approver"), eq(null), any())).thenReturn(Optional.of("bob"));
    publisher.reviewCompleted(5L, "kevin");
    final ArgumentCaptor<EcwsTaskOutbox> saved = ArgumentCaptor.forClass(EcwsTaskOutbox.class);
    verify(outbox).save(saved.capture());
    assertThat(saved.getValue().getAssigneeLogin()).isEqualTo("bob");
  }

  @Test
  void nothingIsQueuedWithoutAnOpenApprovalTask() {
    when(outbox.reassignOpen(eq(5L), eq("APPROVAL"), any())).thenReturn(0);
    publisher.approverChanged(5L, "alice", "kevin");
    verify(outbox, never()).save(any());
  }

  @Test
  void nothingIsQueuedWhenTheIntegrationIsOff() {
    props.setEnabled(false);
    publisher.approverChanged(5L, "alice", "kevin");
    verify(outbox, never()).reassignOpen(any(), any(), any());
  }
}
