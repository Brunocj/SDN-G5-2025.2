package net.floodlightcontroller.packetinmanager;

import java.util.List;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packetinmanager.web.AllowedResource;
import org.projectfloodlight.openflow.types.IPv4Address;

public interface IAccessControlService extends IFloodlightService {
    void allowAccessToResources(IPv4Address userIp, List<AllowedResource> resources);
}