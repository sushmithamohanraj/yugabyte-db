// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.subtasks.check;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.BAD_REQUEST;

import com.yugabyte.yw.commissioner.AbstractTaskBase;
import com.yugabyte.yw.commissioner.tasks.CommissionerBaseTest;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.gflags.GFlagDetails;
import com.yugabyte.yw.models.Universe;
import java.util.Arrays;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.yb.client.YBClient;

public class CheckXUniverseAutoFlagsTest extends CommissionerBaseTest {

  private Universe sourceUniverse;
  private Universe targetUniverse;
  private YBClient mockClient;

  @Before
  public void setUp() {
    defaultCustomer = ModelFactory.testCustomer();
    sourceUniverse = ModelFactory.createUniverse("source-universe");
    targetUniverse = ModelFactory.createUniverse("target-universe");
    mockClient = mock(YBClient.class);
    try {
      when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
      doCallRealMethod()
          .when(mockGFlagsValidation)
          .getFilteredAutoFlagsWithNonInitialValue(anyMap(), anyString(), any());
      doCallRealMethod().when(mockGFlagsValidation).isAutoFlag(any());
    } catch (Exception ignored) {
      fail();
    }
  }

  @Test
  public void testAutoFlagCheckSuccess() throws Exception {
    when(mockAutoFlagUtil.getPromotedAutoFlags(any(), any(), anyInt()))
        .thenReturn(Set.of("FLAG_1", "FLAG_2"));
    GFlagDetails flagDetails = new GFlagDetails();
    flagDetails.name = "FLAG_1";
    flagDetails.target = "target";
    flagDetails.initial = "initial";
    flagDetails.tags = "auto";
    GFlagDetails flagDetails2 = new GFlagDetails();
    flagDetails2.name = "FLAG_2";
    flagDetails2.target = "target";
    flagDetails2.initial = "initial";
    flagDetails2.tags = "auto";
    when(mockGFlagsValidation.listAllAutoFlags(anyString(), anyString()))
        .thenReturn(Arrays.asList(flagDetails, flagDetails2));
    CheckXUniverseAutoFlags task = AbstractTaskBase.createTask(CheckXUniverseAutoFlags.class);
    CheckXUniverseAutoFlags.Params params = new CheckXUniverseAutoFlags.Params();
    params.sourceUniverseUUID = sourceUniverse.getUniverseUUID();
    params.targetUniverseUUID = targetUniverse.getUniverseUUID();
    task.initialize(params);
    task.run();
  }

  @Test
  public void testAutoFlagFailure() throws Exception {
    when(mockAutoFlagUtil.getPromotedAutoFlags(any(), any(), anyInt()))
        .thenReturn(Set.of("FLAG_1", "FLAG_2"));
    GFlagDetails flagDetails = new GFlagDetails();
    flagDetails.name = "FLAG_1";
    flagDetails.target = "target";
    flagDetails.initial = "initial";
    flagDetails.tags = "auto";
    GFlagDetails flagDetails2 = new GFlagDetails();
    flagDetails2.name = "FLAG_2";
    flagDetails2.target = "target";
    flagDetails2.initial = "initial";
    flagDetails2.tags = "auto";
    when(mockGFlagsValidation.listAllAutoFlags(anyString(), anyString()))
        .thenReturn(Arrays.asList(flagDetails));
    CheckXUniverseAutoFlags task = AbstractTaskBase.createTask(CheckXUniverseAutoFlags.class);
    CheckXUniverseAutoFlags.Params params = new CheckXUniverseAutoFlags.Params();
    params.sourceUniverseUUID = sourceUniverse.getUniverseUUID();
    params.targetUniverseUUID = targetUniverse.getUniverseUUID();
    task.initialize(params);
    PlatformServiceException pe = assertThrows(PlatformServiceException.class, () -> task.run());
    assertEquals(BAD_REQUEST, pe.getHttpStatus());
    assertEquals(
        "Auto Flag FLAG_2 set on universe "
            + sourceUniverse.getUniverseUUID()
            + " is not present on universe "
            + targetUniverse.getUniverseUUID(),
        pe.getMessage());
  }
}
