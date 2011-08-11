package org.openstack.atlas.api.mgmt.resources;

import org.openstack.atlas.api.helpers.ResponseFactory;
import org.openstack.atlas.api.mgmt.resources.providers.ManagementDependencyProvider;
import org.openstack.atlas.docs.loadbalancers.api.v1.Errorpage;
import org.openstack.atlas.service.domain.operations.Operation;
import org.openstack.atlas.service.domain.pojos.MessageDataContainer;
import org.openstack.atlas.service.domain.util.Constants;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.core.Response;


public class ErrorpageResource extends ManagementDependencyProvider {

    @GET
    public Response retrieveErrorpage() {
        Errorpage errorpage = new Errorpage();
        String errorcontent;

        try {
            errorcontent = loadBalancerService.getDefaultErrorPage();
            errorpage.setContent(errorcontent);
        } catch (Exception ex) {
            return ResponseFactory.getErrorResponse(ex, null, null);
        }

        errorpage.setContent(errorcontent);
        Response resp = Response.status(200).entity(errorpage).build();
        return resp;
    }

    @DELETE
    public Response deleteErrorpage() {
        try {
            getManagementAsyncService().callAsyncLoadBalancingOperation(Operation.DELETE_ERROR_PAGE, new MessageDataContainer());
        } catch (Exception ex) {
            return ResponseFactory.getErrorResponse(ex, null, null);
        }

        Response resp = Response.status(200).build();
        return resp;
    }

    @PUT
    public Response setDefaultErrorPage(Errorpage errorpage) {
        MessageDataContainer dataContainer;
        String content = errorpage.getContent();

        if (content == null) {
            return getValidationFaultResponse("You must provide Content to set ErrorPage");
        } else if (content.length() > Constants.MAX_ERRORPAGE_CONTENT_LENGTH) {
            String msg = String.format("Your content length must be less than %d bytes\n", Constants.MAX_ERRORPAGE_CONTENT_LENGTH);
            return getValidationFaultResponse(msg);
        }

        try {
            loadBalancerService.setDefaultErrorPage(content);
        } catch (Exception ex) {
            return ResponseFactory.getErrorResponse(ex, null, null);
        }

        dataContainer = new MessageDataContainer();
        dataContainer.setErrorFileContents(content);
        try {
            getManagementAsyncService().callAsyncLoadBalancingOperation(Operation.UPDATE_ERRORFILE, dataContainer);
        } catch (Exception ex) {
            return ResponseFactory.getErrorResponse(ex, null, null);
        }

        return Response.status(202).build();
    }
}
