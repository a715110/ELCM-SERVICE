package com.dodaso.ecosystem.elcm.integration.ecws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ADDED 2026-11-07 -- the member list sent with each ECWS task. */
class EcwsTaskMembersTest {

  private static EcwsTaskPublisher.Ctx ctx(final String submitter, final String assignee) {
    return new EcwsTaskPublisher.Ctx(1L, "PKG-2026-001", "ws", submitter, assignee);
  }

  @Test
  void submitterAndReviewerOnly() {
    assertEquals(List.of("susan", "kevin"), EcwsTaskPublisher.members(ctx("susan", "kevin")));
  }

  @Test
  void allThreeDifferentAreAllListed() {
    assertEquals(List.of("susan", "kevin", "alice", "bob"),
        EcwsTaskPublisher.members(ctx("susan", "kevin"), "alice", "bob"));
  }

  @Test
  void samePersonInAllRolesIsListedOnceWhateverTheCase() {
    assertEquals(List.of("Byoung"),
        EcwsTaskPublisher.members(ctx("Byoung", "byoung"), "BYOUNG", "byoung"));
  }

  @Test
  void blankNullAndSystemAreDropped() {
    assertEquals(List.of("susan"),
        EcwsTaskPublisher.members(ctx("susan", null), null, " ", "system"));
  }

  @Test
  void joinAndSplitRoundTrip() {
    final EcwsTaskOutbox row = new EcwsTaskOutbox();
    row.setMemberLogins(EcwsTaskPublisher.joinLogins(List.of("susan", "kevin", "alice")));
    assertEquals("susan,kevin,alice", row.getMemberLogins());
    assertEquals(List.of("susan", "kevin", "alice"), EcwsTaskClient.membersOf(row));
  }

  @Test
  void emptyListIsNull() {
    assertNull(EcwsTaskPublisher.joinLogins(List.of()));
    assertEquals(List.of(), EcwsTaskClient.membersOf(new EcwsTaskOutbox()));
  }

  @Test
  void extractionHasItsOwnTaskType() {
    assertEquals("ELCM Extraction", EcwsTaskClient.taskTypeName(EcwsTaskPublisher.EXTRACTION));
  }
}
