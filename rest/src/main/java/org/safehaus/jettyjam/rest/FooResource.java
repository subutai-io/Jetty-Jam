package org.safehaus.jettyjam.rest;


import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;


/**
 * A basic JSON rest service endpoint.
 */
@Singleton
@Produces( MediaType.APPLICATION_JSON )
@Path( FooResource.ENDPOINT_URL )
public class FooResource {
    private static final Logger LOG = LoggerFactory.getLogger( FooResource.class );
    public static final String ENDPOINT_URL = "/rest/foo";
    public static final String JSON_MESSAGE = "{ \"msg\": \"Hello World\" }";

    @GET
    public Response foo() {
        LOG.info( "Foo service called ..." );
        return Response.status( Response.Status.OK ).entity( JSON_MESSAGE ).build();
    }
}
