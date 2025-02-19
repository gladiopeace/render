package org.janelia.render.service;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

import org.bson.types.ObjectId;
import org.janelia.alignment.spec.Bounds;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.MipmapPathBuilder;
import org.janelia.alignment.spec.stack.ReconstructionCycle;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.spec.stack.StackStats;
import org.janelia.alignment.spec.stack.StackVersion;
import org.janelia.render.service.dao.RenderDao;
import org.janelia.render.service.model.IllegalServiceArgumentException;
import org.janelia.render.service.model.ObjectNotFoundException;
import org.janelia.render.service.util.RenderServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import static org.janelia.alignment.spec.stack.StackMetaData.StackState;
import static org.janelia.alignment.spec.stack.StackMetaData.StackState.*;

/**
 * APIs for accessing stack meta data stored in the Render service database.
 *
 * @author Eric Trautman
 */
@Path("/")
@Api(tags = {"Stack Data APIs"})
public class StackMetaDataService {

    private final RenderDao renderDao;

    @SuppressWarnings("UnusedDeclaration")
    public StackMetaDataService()
            throws UnknownHostException {
        this(RenderDao.build());
    }

    StackMetaDataService(final RenderDao renderDao) {
        this.renderDao = renderDao;
    }

    @Path("v1/likelyUniqueId")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "A (very likely) globally unique identifier")
    public String getUniqueId() {
        final ObjectId objectId = new ObjectId();
        return objectId.toString();
    }

    @Path("v1/owners")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of all data owners")
    public List<String> getOwners() {
        LOG.info("getOwners: entry");
        return renderDao.getOwners();
    }

    @Path("v1/owner/{owner}/projects")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of projects for the specified owner")
    public List<String> getProjectsForOwner(@PathParam("owner") final String owner) {
        List<String> list = null;
        try {
            list = renderDao.getProjects(owner);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("v1/owner/{owner}/stackIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of stack identifiers for the specified owner")
    public List<StackId> getStackIdsForOwner(@PathParam("owner") final String owner) {
        return getStackIdsForProject(owner, null);
    }

    @Path("v1/owner/{owner}/project/{project}/stackIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of stack identifiers for the specified project")
    public List<StackId> getStackIdsForProject(@PathParam("owner") final String owner,
                                               @PathParam("project") final String project) {

        LOG.info("getStackIdsForProject: entry, owner={}, project={}", owner, project);

        final List<StackMetaData> stackMetaDataList = getStackMetaDataListForProject(owner, project);
        final List<StackId> list = new ArrayList<>(stackMetaDataList.size());
        //noinspection Convert2streamapi
        for (final StackMetaData stackMetaData : stackMetaDataList) {
            list.add(stackMetaData.getStackId());
        }

        return list;
    }

    @Path("v1/owner/{owner}/stacks")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of stack metadata for the specified owner")
    public List<StackMetaData> getStackMetaDataListForOwner(@PathParam("owner") final String owner) {
        return getStackMetaDataListForProject(owner, null);
    }

    @Path("v1/owner/{owner}/project/{project}/stacks")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List of stack metadata for the specified project")
    public List<StackMetaData> getStackMetaDataListForProject(@PathParam("owner") final String owner,
                                                              @PathParam("project") final String project) {

        LOG.info("getStackMetaDataListForProject: entry, owner={}, project={}", owner, project);

        List<StackMetaData> list = null;
        try {
            list = renderDao.getStackMetaDataList(owner, project);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }
        return list;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Metadata for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public StackMetaData getStackMetaData(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack) {

        LOG.info("getStackMetaData: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        StackMetaData stackMetaData = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            stackMetaData = renderDao.getStackMetaData(stackId);
            if (stackMetaData == null) {
                throw getStackNotFoundException(owner, project, stack);
            }
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return stackMetaData;
    }

    @Path("v1/owner/{owner}/project/{fromProject}/stack/{fromStack}/stackId")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Rename a stack")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "stack successfully renamed"),
            @ApiResponse(code = 400, message = "stack cannot be renamed"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response renameStack(@PathParam("owner") final String owner,
                                @PathParam("fromProject") final String fromProject,
                                @PathParam("fromStack") final String fromStack,
                                @Context final UriInfo uriInfo,
                                final StackId toStackId) {

        LOG.info("renameStack: entry, owner={}, fromProject={}, fromStack={}, toStackId={}",
                 owner, fromProject, fromStack, toStackId);

        try {
            final StackId fromStackId = new StackId(owner, fromProject, fromStack);

            renderDao.renameStack(fromStackId, toStackId);

            LOG.info("renameStack: renamed {} to {}", fromStackId, toStackId);

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{fromProject}/stack/{fromStack}/cloneTo/{toStack}")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Clones one stack to another",
            notes = "This operation copies all fromStack tiles and transformations to a new stack with the specified metadata.  This is a potentially long running operation (depending upon the size of the fromStack).")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "stack successfully cloned"),
            @ApiResponse(code = 400, message = "toStack is not in LOADING state"),
            @ApiResponse(code = 404, message = "fromStack not found")
    })
    public Response cloneStackVersion(@PathParam("owner") final String owner,
                                      @PathParam("fromProject") final String fromProject,
                                      @PathParam("fromStack") final String fromStack,
                                      @PathParam("toStack") final String toStack,
                                      @QueryParam("z") final List<Double> zValues,
                                      @QueryParam("toProject") String toProject,
                                      @QueryParam("skipTransforms") final Boolean skipTransforms,
                                      @Context final UriInfo uriInfo,
                                      final StackVersion stackVersion) {

        LOG.info("cloneStackVersion: entry, owner={}, fromProject={}, fromStack={}, toProject={}, toStack={}, zValues={}, stackVersion={}",
                 owner, fromProject, fromStack, toProject, toStack, zValues, stackVersion);

        try {
            if (stackVersion == null) {
                throw new IllegalArgumentException("no stack version provided");
            }

            if (toProject == null) {
                toProject = fromProject;
            }

            final StackMetaData fromStackMetaData = getStackMetaData(owner, fromProject, fromStack);
            final StackId toStackId = new StackId(owner, toProject, toStack);

            StackMetaData toStackMetaData = renderDao.getStackMetaData(toStackId);

            if ((toStackMetaData != null) && (! toStackMetaData.isLoading())) {
                throw new IllegalStateException("Tiles cannot be cloned to stack " + toStack +
                                                " because it is " + toStackMetaData.getState() + ".");
            }

            renderDao.cloneStack(fromStackMetaData.getStackId(), toStackId, zValues, skipTransforms);

            toStackMetaData = new StackMetaData(toStackId, stackVersion);
            renderDao.saveStackMetaData(toStackMetaData);

            LOG.info("cloneStackVersion: created {} from {}", toStackId, fromStackMetaData.getStackId());

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}")
    @POST  // NOTE: POST method is used because version number is auto-incremented
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Saves new version of stack metadata")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "stackVersion successfully created"),
            @ApiResponse(code = 400, message = "stackVersion not specified or stack is READ_ONLY")
    })
    public Response saveStackVersion(@PathParam("owner") final String owner,
                                     @PathParam("project") final String project,
                                     @PathParam("stack") final String stack,
                                     @Context final UriInfo uriInfo,
                                     final StackVersion stackVersion) {

        LOG.info("saveStackVersion: entry, owner={}, project={}, stack={}, stackVersion={}",
                 owner, project, stack, stackVersion);

        try {
            if (stackVersion == null) {
                throw new IllegalArgumentException("no stack version provided");
            }

            final StackId stackId = new StackId(owner, project, stack);

            StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
            if (stackMetaData == null) {
                stackMetaData = new StackMetaData(stackId, stackVersion);
            } else {
                validateStackIsModifiable(stackMetaData);
                stackMetaData = stackMetaData.getNextVersion(stackVersion);
            }

            renderDao.saveStackMetaData(stackMetaData);

            LOG.info("saveStackVersion: saved version number {}", stackMetaData.getCurrentVersionNumber());

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}")
    @DELETE
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Deletes specified stack",
            notes = "Deletes all tiles, transformations, meta data, and unsaved snapshot data for the stack.")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack is READ_ONLY")
    })
    public Response deleteStack(@PathParam("owner") final String owner,
                                @PathParam("project") final String project,
                                @PathParam("stack") final String stack) {

        LOG.info("deleteStack: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);

            final StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
            if (stackMetaData == null) {

                LOG.info("deleteStack: {} is already gone, nothing to do", stackId);

            } else {

                validateStackIsModifiable(stackMetaData);
                renderDao.removeStack(stackId, true);

            }

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/resolutionValues")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "The x, y, and z resolution values for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public List<Double> getResolutionValues(@PathParam("owner") final String owner,
                                            @PathParam("project") final String project,
                                            @PathParam("stack") final String stack) {

        LOG.info("getResolutionValues: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        List<Double> resolutionValues = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            resolutionValues = stackMetaData.getCurrentResolutionValues();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return resolutionValues;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/resolutionValues")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Saves x, y, and z resolution values for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "resolution values successfully saved"),
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response saveResolutionValues(@PathParam("owner") final String owner,
                                         @PathParam("project") final String project,
                                         @PathParam("stack") final String stack,
                                         final List<Double> resolutionValues) {

        LOG.info("saveResolutionValues: entry, owner={}, project={}, stack={}, resolutionValues={}",
                 owner, project, stack, resolutionValues);

        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            validateStackIsModifiable(stackMetaData);
            stackMetaData.setCurrentResolutionValues(resolutionValues);
            renderDao.saveStackMetaData(stackMetaData);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/resolutionValues")
    @DELETE
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Deletes x, y, and z resolution values for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response deleteResolutionValues(@PathParam("owner") final String owner,
                                           @PathParam("project") final String project,
                                           @PathParam("stack") final String stack) {
        return saveResolutionValues(owner, project, stack, Arrays.asList(null, null, null));
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/materializedBoxRootPath")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @ApiOperation(
            value = "The materializedBoxRootPath for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public String getMaterializedBoxRootPath(@PathParam("owner") final String owner,
                                             @PathParam("project") final String project,
                                             @PathParam("stack") final String stack) {

        LOG.info("getMaterializedBoxRootPath: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        String materializedBoxRootPath = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            materializedBoxRootPath = stackMetaData.getCurrentMaterializedBoxRootPath();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return materializedBoxRootPath;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/materializedBoxRootPath")
    @PUT
    @Consumes(MediaType.TEXT_PLAIN)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Saves materializedBoxRootPath for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "materializedBoxRootPath successfully saved"),
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response saveMaterializedBoxRootPath(@PathParam("owner") final String owner,
                                                @PathParam("project") final String project,
                                                @PathParam("stack") final String stack,
                                                final String materializedBoxRootPath) {

        LOG.info("saveMaterializedBoxRootPath: entry, owner={}, project={}, stack={}, materializedBoxRootPath={}",
                 owner, project, stack, materializedBoxRootPath);

        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            validateStackIsModifiable(stackMetaData);
            stackMetaData.setCurrentMaterializedBoxRootPath(materializedBoxRootPath);
            renderDao.saveStackMetaData(stackMetaData);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/materializedBoxRootPath")
    @DELETE
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Deletes materializedBoxRootPath for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response deleteMaterializedBoxRootPath(@PathParam("owner") final String owner,
                                                  @PathParam("project") final String project,
                                                  @PathParam("stack") final String stack) {
        return saveMaterializedBoxRootPath(owner, project, stack, null);
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/mipmapPathBuilder")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "The mipmap path builder specs for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public MipmapPathBuilder getMipmapPathBuilder(@PathParam("owner") final String owner,
                                                 @PathParam("project") final String project,
                                                 @PathParam("stack") final String stack) {

        LOG.info("getMipmapPathBuilder: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        MipmapPathBuilder mipmapPathBuilder = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            mipmapPathBuilder = stackMetaData.getCurrentMipmapPathBuilder();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return mipmapPathBuilder;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/mipmapPathBuilder")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Saves mipmap path builder specs for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "mipmap path builder specs successfully saved"),
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response saveMipmapPathBuilder(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          final MipmapPathBuilder mipmapPathBuilder) {

        LOG.info("saveMipmapPathBuilder: entry, owner={}, project={}, stack={}, mipmapPathBuilder={}",
                 owner, project, stack, mipmapPathBuilder);

        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            validateStackIsModifiable(stackMetaData);
            stackMetaData.setCurrentMipmapPathBuilder(mipmapPathBuilder);
            renderDao.saveStackMetaData(stackMetaData);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/mipmapPathBuilder")
    @DELETE
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Deletes mipmap path builder specs for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response deleteMipmapPathBuilder(@PathParam("owner") final String owner,
                                            @PathParam("project") final String project,
                                            @PathParam("stack") final String stack) {
        return saveMipmapPathBuilder(owner, project, stack, null);
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/defaultChannel")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "The default channel for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public String getDefaultChannel(@PathParam("owner") final String owner,
                                    @PathParam("project") final String project,
                                    @PathParam("stack") final String stack) {

        LOG.info("getDefaultChannel: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        String defaultChannel = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            defaultChannel = stackMetaData.getCurrentDefaultChannel();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return defaultChannel;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/defaultChannel")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Saves default channel name for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "default channel successfully saved"),
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response saveDefaultChannel(@PathParam("owner") final String owner,
                                       @PathParam("project") final String project,
                                       @PathParam("stack") final String stack,
                                       final String defaultChannel) {

        LOG.info("saveDefaultChannel: entry, owner={}, project={}, stack={}, defaultChannel={}",
                 owner, project, stack, defaultChannel);

        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            validateStackIsModifiable(stackMetaData);
            stackMetaData.setCurrentDefaultChannel(defaultChannel);
            renderDao.saveStackMetaData(stackMetaData);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/defaultChannel")
    @DELETE
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Deletes default channel for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response deleteDefaultChannel(@PathParam("owner") final String owner,
                                         @PathParam("project") final String project,
                                         @PathParam("stack") final String stack) {
        return saveDefaultChannel(owner, project, stack, null);
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/cycle")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "The cycle data for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "Stack not found")
    })
    public ReconstructionCycle getCycle(@PathParam("owner") final String owner,
                                        @PathParam("project") final String project,
                                        @PathParam("stack") final String stack) {

        LOG.info("getCycleNumber: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        ReconstructionCycle cycle = new ReconstructionCycle();
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            final StackVersion stackVersion = stackMetaData.getCurrentVersion();
            if (stackVersion != null) {
                cycle = stackVersion.getCycle();
            }
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return cycle;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/cycle")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Saves cycle data for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "cycle data successfully saved"),
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response saveCycle(@PathParam("owner") final String owner,
                              @PathParam("project") final String project,
                              @PathParam("stack") final String stack,
                              final ReconstructionCycle cycle) {

        LOG.info("saveCycleNumber: entry, owner={}, project={}, stack={}, cycle={}",
                 owner, project, stack, cycle);

        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            validateStackIsModifiable(stackMetaData);
            final StackVersion stackVersion = stackMetaData.getCurrentVersion();
            if (stackVersion != null) {
                stackVersion.setCycle(cycle);
            }
            renderDao.saveStackMetaData(stackMetaData);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return Response.ok().build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/cycle")
    @DELETE
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Deletes cycle data for stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack is READ_ONLY"),
            @ApiResponse(code = 404, message = "stack not found")
    })
    public Response deleteCycleNumber(@PathParam("owner") final String owner,
                                      @PathParam("project") final String project,
                                      @PathParam("stack") final String stack) {
        return saveCycle(owner, project, stack, null);
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/section/{sectionId}")
    @DELETE
    @ApiOperation(
            tags = {"Section Data APIs"},
            value = "Deletes all tiles in section",
            notes = "Deletes all tiles in the specified stack with the specified sectionId value.  This operation can only be performed against stacks in the LOADING state")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack state is not LOADING"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response deleteStackTilesWithSectionId(@PathParam("owner") final String owner,
                                                  @PathParam("project") final String project,
                                                  @PathParam("stack") final String stack,
                                                  @PathParam("sectionId") final String sectionId) {

        LOG.info("deleteStackTilesWithSectionId: entry, owner={}, project={}, stack={}, sectionId={}",
                 owner, project, stack, sectionId);

        Response response = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);

            if (! stackMetaData.isLoading()) {
                throw new IllegalArgumentException("stack state is " + stackMetaData.getState() +
                                                   " but must be LOADING to delete data");
            }

            renderDao.removeTilesWithSectionId(stackMetaData.getStackId(), sectionId);

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/z/{z}")
    @DELETE
    @ApiOperation(
            tags = {"Section Data APIs"},
            value = "Deletes all tiles in layer",
            notes = "Deletes all tiles in the specified stack with the specified z value.  This operation can only be performed against stacks in the LOADING state")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack state is not LOADING"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response deleteStackTilesWithZ(@PathParam("owner") final String owner,
                                          @PathParam("project") final String project,
                                          @PathParam("stack") final String stack,
                                          @PathParam("z") final Double z) {

        LOG.info("deleteStackTilesWithZ: entry, owner={}, project={}, stack={}, z={}",
                 owner, project, stack, z);

        Response response = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);

            if (! stackMetaData.isLoading()) {
                throw new IllegalArgumentException("stack state is " + stackMetaData.getState() +
                                                   " but must be LOADING to delete data");
            }

            renderDao.removeTilesWithZ(stackMetaData.getStackId(), z);

            response = Response.ok().build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/state/{state}")
    @PUT
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Sets the stack's current state",
            notes = "Normal progression is LOADING to COMPLETE to READ_ONLY to OFFLINE.  " +
                    "Transitioning to COMPLETE is a potentially long running operation " +
                    "since it creates indexes and aggregates meta data.  " +
                    "Transitioning to OFFLINE assumes that the stack data has been persisted elsewhere " +
                    "(e.g. a database dump file) and will remove the stack tile and transform collections, " +
                    "so BE CAREFUL when transitioning to OFFLINE!")
    @ApiResponses(value = {
            @ApiResponse(code = 201, message = "state successfully changed"),
            @ApiResponse(code = 400, message = "stack state cannot be changed because of current state"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Response setStackState(@PathParam("owner") final String owner,
                                  @PathParam("project") final String project,
                                  @PathParam("stack") final String stack,
                                  @PathParam("state") final StackState state,
                                  @Context final UriInfo uriInfo) {

        LOG.info("setStackState: entry, owner={}, project={}, stack={}, state={}",
                 owner, project, stack, state);

        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            final StackState currentState = stackMetaData.getState();

            stackMetaData.validateStateChange(state);

            if (COMPLETE.equals(state)) {

                if (READ_ONLY.equals(currentState)) {
                    stackMetaData.setState(state);
                    renderDao.saveStackMetaData(stackMetaData);
                } else {
                    renderDao.ensureIndexesAndDeriveStats(stackMetaData); // also sets state to COMPLETE
                }

            } else if (OFFLINE.equals(state)) {

                stackMetaData.setState(state);
                renderDao.saveStackMetaData(stackMetaData);
                renderDao.removeStack(stackMetaData.getStackId(), false);

            } else { // LOADING

                stackMetaData.setState(state);
                renderDao.saveStackMetaData(stackMetaData);
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        final Response.ResponseBuilder responseBuilder = Response.created(uriInfo.getRequestUri());

        return responseBuilder.build();
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/bounds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Bounds for the specified stack")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "stack bounds not available"),
            @ApiResponse(code = 404, message = "stack not found"),
    })
    public Bounds getStackBounds(@PathParam("owner") final String owner,
                                 @PathParam("project") final String project,
                                 @PathParam("stack") final String stack) {

        LOG.info("getStackBounds: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Bounds bounds = null;
        try {
            final StackMetaData stackMetaData = getStackMetaData(owner, project, stack);
            final StackStats stats = stackMetaData.getStats();

            String errorCondition = null;

            if (stats == null) {
                errorCondition = " have not been stored.  ";
            } else {
                bounds = stats.getStackBounds();
                if (bounds == null) {
                    errorCondition = " have been stored without bounds.  ";
                }
            }

            if (errorCondition != null) {
                throw new IllegalServiceArgumentException(
                        "Stats for " + stackMetaData.getStackId() + errorCondition +
                        "If all data has been loaded for this stack, setting its state to " +
                        "COMPLETE will trigger derivation and storage of all the stack's stats.");
            }

        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return bounds;
    }

    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tileIds")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            tags = {"Stack Data APIs"},
            value = "Tile IDs for the specified stack",
            responseContainer = "List",
            response = String.class,
            notes = "For stacks with large numbers of tiles, this will produce a large amount of data (e.g. 500MB for 18 million tiles) - use wisely.")
    public Response getStackTileIds(@PathParam("owner") final String owner,
                                    @PathParam("project") final String project,
                                    @PathParam("stack") final String stack,
                                    @QueryParam("minZ") final Double minZ,
                                    @QueryParam("maxZ") final Double maxZ,
                                    @QueryParam("matchPattern") final String matchPattern) {

        LOG.info("getStackTileIds: entry, owner={}, project={}, stack={}",
                 owner, project, stack);

        Response response = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);

            final StreamingOutput responseOutput = output -> renderDao.writeTileIds(stackId,
                                                                                    minZ,
                                                                                    maxZ,
                                                                                    matchPattern,
                                                                                    output);
            response = Response.ok(responseOutput).build();
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return response;
    }

    /**
     * @return list of tile specs with specified ids.
     *         Tile specs will contain with flattened (and therefore resolved)
     *         transform specs suitable for external use.
     */
    @Path("v1/owner/{owner}/project/{project}/stack/{stack}/tile-specs-with-ids")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get flattened tile specs with the specified ids",
            notes = "For each tile spec, nested transform lists are flattened and reference transforms are resolved.  This should make the specs suitable for external use.")
    public List<TileSpec> getTileSpecsWithIds(@PathParam("owner") final String owner,
                                              @PathParam("project") final String project,
                                              @PathParam("stack") final String stack,
                                              final List<String> tileIdList) {

        LOG.info("getTileSpecsWithIds: entry, owner={}, project={}, stack={}, tileIdList.size()={}",
                 owner, project, stack, tileIdList.size());

        List<TileSpec> tileSpecList = null;
        try {
            final StackId stackId = new StackId(owner, project, stack);
            tileSpecList = renderDao.getTileSpecs(stackId, tileIdList);
            tileSpecList.forEach(TileSpec::flattenTransforms);
        } catch (final Throwable t) {
            RenderServiceUtil.throwServiceException(t);
        }

        return tileSpecList;
    }

    public static StackMetaData getStackMetaData(final StackId stackId,
                                                 final RenderDao renderDao)
            throws ObjectNotFoundException {

        final StackMetaData stackMetaData = renderDao.getStackMetaData(stackId);
        if (stackMetaData == null) {
            throw getStackNotFoundException(stackId.getOwner(),
                                            stackId.getProject(),
                                            stackId.getStack());
        }
        return stackMetaData;
    }

    static ObjectNotFoundException getStackNotFoundException(final String owner,
                                                             final String project,
                                                             final String stack) {
        return new ObjectNotFoundException("stack with owner '" + owner + "', project '" + project +
                                            "', and name '" + stack + "' does not exist");
    }

    private static void validateStackIsModifiable(final StackMetaData stackMetaData) {
        if (stackMetaData.isReadOnly()) {
            throw new IllegalStateException("Data for stack " + stackMetaData.getStackId().getStack() +
                                            " cannot be modified because it is " + stackMetaData.getState() + ".");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(StackMetaDataService.class);
}
