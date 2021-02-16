package com.github.nhenneaux.jersey.connector.httpclient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

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

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("post")
    public Data post(Data data) {
        return data;
    }


    static class Data {
        private String data;

        public Data() {
        }

        public Data(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }

}
