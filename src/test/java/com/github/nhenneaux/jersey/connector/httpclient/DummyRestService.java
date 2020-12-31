package com.github.nhenneaux.jersey.connector.httpclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.concurrent.TimeUnit;

@Path("/")
public class DummyRestService {

    @HEAD
    @Path("ping")
    public void ping() {
        // Just checking the server is listening
    }

    @GET
    @Path("pingWithSleep")
    public long pingWithSleep(@QueryParam("sleepTimeInMilliseconds") long sleepTimeInMilliseconds) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(sleepTimeInMilliseconds);
        return sleepTimeInMilliseconds;
    }


}
