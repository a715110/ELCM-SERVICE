package com.dodaso.ecosystem.elcm.integration.packagerail;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADDED 2026-10-25 -- one page of Package Rail lines. total is how many packages the caller may
 * see in this list, nextOffset is where the next page starts (null when there is no more) and
 * canSeeAll says whether the caller may use the All view.
 */
@Getter
@Setter
@NoArgsConstructor
public class PackageRailPage {

  private List<PackageRailItem> items = new ArrayList<>();
  private Integer total;
  private Integer nextOffset;
  private boolean canSeeAll;
}
