// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.controllers.handlers.RegionHandler;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPError;
import com.yugabyte.yw.forms.RegionEditFormData;
import com.yugabyte.yw.forms.RegionFormData;
import com.yugabyte.yw.models.Audit;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.helpers.CloudInfoInterface;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.libs.Json;
import play.mvc.Result;

@Api(
    value = "Region management",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class RegionController extends AuthenticatedController {

  @Inject RegionHandler regionHandler;

  public static final Logger LOG = LoggerFactory.getLogger(RegionController.class);
  // This constant defines the minimum # of PlacementAZ we need to tag a region as Multi-PlacementAZ
  // complaint

  @ApiOperation(
      value = "List a provider's regions",
      response = Region.class,
      responseContainer = "List",
      nickname = "getRegion")
  @ApiResponses(
      @io.swagger.annotations.ApiResponse(
          code = 500,
          message = "If there was a server or database issue when listing the regions",
          response = YBPError.class))
  public Result list(UUID customerUUID, UUID providerUUID) {
    List<Region> regionList;

    int minAZCountNeeded = 1;
    regionList = Region.fetchValidRegions(customerUUID, providerUUID, minAZCountNeeded);
    return PlatformResults.withData(regionList);
  }

  @ApiOperation(
      value = "List regions for all providers",
      response = Region.class,
      responseContainer = "List")
  // todo: include provider field in response
  public Result listAllRegions(UUID customerUUID) {
    List<Provider> providerList = Provider.getAll(customerUUID);
    ArrayNode resultArray = Json.newArray();
    for (Provider provider : providerList) {
      CloudInfoInterface.mayBeMassageResponse(provider);
      List<Region> regionList = Region.fetchValidRegions(customerUUID, provider.uuid, 1);
      for (Region region : regionList) {
        ObjectNode regionNode = (ObjectNode) Json.toJson(region);
        ObjectNode providerForRegion = (ObjectNode) Json.toJson(provider);
        providerForRegion.remove("regions"); // to Avoid recursion
        regionNode.set("provider", providerForRegion);
        resultArray.add(regionNode);
      }
    }
    return ok(resultArray);
  }

  /**
   * POST endpoint for creating new region
   *
   * @return JSON response of newly created region
   */
  @ApiOperation(value = "Create a new region", response = Region.class, nickname = "createRegion")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "region",
          value = "region form data for new region to be created",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.RegionFormData",
          required = true))
  public Result create(UUID customerUUID, UUID providerUUID) {
    Form<RegionFormData> formData = formFactory.getFormDataOrBadRequest(RegionFormData.class);
    RegionFormData form = formData.get();
    Region region = regionHandler.createRegion(customerUUID, providerUUID, form);

    auditService()
        .createAuditEntryWithReqBody(
            ctx(),
            Audit.TargetType.Region,
            Objects.toString(region.uuid, null),
            Audit.ActionType.Create,
            Json.toJson(formData.rawData()));
    return PlatformResults.withData(region);
  }

  /**
   * PUT endpoint for modifying an existing Region.
   *
   * @param customerUUID Customer UUID
   * @param providerUUID Provider UUID
   * @param regionUUID Region UUID
   * @return JSON response on whether or not the operation was successful.
   */
  @ApiOperation(value = "Modify a region", response = Object.class, nickname = "editRegion")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "region",
          value = "region edit form data",
          paramType = "body",
          dataType = "com.yugabyte.yw.forms.RegionEditFormData",
          required = true))
  public Result edit(UUID customerUUID, UUID providerUUID, UUID regionUUID) {
    RegionEditFormData form = formFactory.getFormDataOrBadRequest(RegionEditFormData.class).get();
    Region region = regionHandler.editRegion(customerUUID, providerUUID, regionUUID, form);

    auditService()
        .createAuditEntryWithReqBody(
            ctx(), Audit.TargetType.Region, regionUUID.toString(), Audit.ActionType.Edit);
    return PlatformResults.withData(region);
  }

  /**
   * DELETE endpoint for deleting an existing Region.
   *
   * @param customerUUID Customer UUID
   * @param providerUUID Provider UUID
   * @param regionUUID Region UUID
   * @return JSON response on whether the region was successfully deleted.
   */
  @ApiOperation(value = "Delete a region", response = Object.class, nickname = "deleteRegion")
  public Result delete(UUID customerUUID, UUID providerUUID, UUID regionUUID) {
    Region region = regionHandler.deleteRegion(customerUUID, providerUUID, regionUUID);

    auditService()
        .createAuditEntryWithReqBody(
            ctx(), Audit.TargetType.Region, regionUUID.toString(), Audit.ActionType.Delete);
    return PlatformResults.withData(region);
  }
}
