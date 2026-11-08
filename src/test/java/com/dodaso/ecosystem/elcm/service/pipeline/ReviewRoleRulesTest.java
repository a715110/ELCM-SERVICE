package com.dodaso.ecosystem.elcm.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dodaso.ecosystem.auth.dto.UserDirectoryDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.Submission;
import com.dodaso.ecosystem.elcm.integration.ecws.EcwsIntegrationProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** ADDED 2026-11-07. The reviewer and approver checks at submit, review completion and change. */
class ReviewRoleRulesTest {

  private AssigneeDirectoryLookupService directory;
  private EcwsIntegrationProperties props;
  private ReviewRoleRules rules;

  private static UserDirectoryDTO user(final String login) {
    final UserDirectoryDTO dto = mock(UserDirectoryDTO.class);
    when(dto.getLoginId()).thenReturn(login);
    when(dto.getDisplayName()).thenReturn(login.toUpperCase());
    return dto;
  }

  private static Submission submission(final String submitter, final String reviewer) {
    final Submission s = mock(Submission.class);
    when(s.getSubmittedBy()).thenReturn(submitter);
    when(s.getReviewedBy()).thenReturn(reviewer);
    return s;
  }

  private void directoryHas(final String... logins) {
    for (final String login : logins) {
      when(directory.findByAssigneeId(login)).thenReturn(Optional.of(user(login)));
    }
  }

  @BeforeEach
  void setUp() {
    directory = mock(AssigneeDirectoryLookupService.class);
    props = new EcwsIntegrationProperties();
    rules = new ReviewRoleRules(props, directory);
    ReflectionTestUtils.setField(rules, "separationOn", true);
    directoryHas("susan", "kevin", "alice", "bob");
    when(directory.findAllWithRole("Reviewer")).thenReturn(List.of(user("kevin"), user("susan")));
    when(directory.findAllWithRole("Approver")).thenReturn(List.of(user("alice"), user("kevin")));
  }

  private void assertRefused(final Runnable call) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED));
  }

  @Test
  void submitPassesWhenAssigneeReviewsAndAnApproverRemains() {
    rules.checkSubmit("susan", "kevin");
  }

  @Test
  void submitRefusedWhenAssigneeIsTheSubmitter() {
    assertRefused(() -> rules.checkSubmit("susan", "susan"));
  }

  @Test
  void submitRefusedWhenAssigneeLacksTheReviewerRole() {
    assertRefused(() -> rules.checkSubmit("susan", "bob"));
  }

  @Test
  void submitRefusedWhenAssigneeIsUnknown() {
    assertRefused(() -> rules.checkSubmit("susan", "nobody"));
  }

  @Test
  void submitRefusedWhenTheOnlyApproverIsTheAssignee() {
    when(directory.findAllWithRole("Approver")).thenReturn(List.of(user("kevin")));
    assertRefused(() -> rules.checkSubmit("susan", "kevin"));
  }

  @Test
  void ruleOffAllowsOnePersonInEveryPlace() {
    ReflectionTestUtils.setField(rules, "separationOn", false);
    when(directory.findAllWithRole("Approver")).thenReturn(List.of(user("kevin")));
    rules.checkSubmit("kevin", "kevin");
  }

  @Test
  void checksCanBeSwitchedOff() {
    props.setValidateRoles(false);
    rules.checkSubmit("susan", "nobody");
    rules.checkReviewCompleted(submission("susan", null), "kevin");
  }

  @Test
  void reviewCompletionNeedsAnApproverOtherThanSubmitterAndReviewer() {
    rules.checkReviewCompleted(submission("susan", null), "kevin");
    when(directory.findAllWithRole("Approver")).thenReturn(List.of(user("kevin"), user("susan")));
    assertRefused(() -> rules.checkReviewCompleted(submission("susan", null), "kevin"));
  }

  @Test
  void newApproverMustBeEligible() {
    final Submission s = submission("susan", "kevin");
    assertThat(rules.checkNewApprover(s, "alice")).isEqualTo("alice");
    assertRefused(() -> rules.checkNewApprover(s, "kevin"));
    assertRefused(() -> rules.checkNewApprover(s, "bob"));
    assertRefused(() -> rules.checkNewApprover(s, "nobody"));
  }

  @Test
  void approverPickedWhileCompletingMustBeEligible() {
    assertThat(rules.checkApprover("susan", "kevin", "alice")).isEqualTo("alice");
    assertRefused(() -> rules.checkApprover("susan", "kevin", "kevin"));
    assertRefused(() -> rules.checkApprover("susan", "kevin", "susan"));
    assertRefused(() -> rules.checkApprover("susan", "kevin", "bob"));
  }

  @Test
  void candidateListsLeaveOutTheExcludedPeople() {
    assertThat(rules.reviewerCandidates("susan")).extracting(UserDirectoryDTO::getLoginId)
        .containsExactly("kevin");
    assertThat(rules.approverCandidates("susan", "kevin")).extracting(UserDirectoryDTO::getLoginId)
        .containsExactly("alice");
  }
}
