package net.floodlightcontroller.packetinmanager.web;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AllowedResource {
    private String ip;
    private int port;
    private String protocol;

    @JsonProperty("ip")
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    @JsonProperty("port")
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    @JsonProperty("protocol")
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
}