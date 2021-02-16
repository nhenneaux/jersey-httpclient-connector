package com.github.nhenneaux.jersey.connector.httpclient;


import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
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
