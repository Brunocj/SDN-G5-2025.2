package net.floodlightcontroller.packetinmanager.web;

import net.floodlightcontroller.restserver.RestletRoutable;
import org.restlet.Context;
import org.restlet.routing.Router;

public class AccessControlWebRoutable implements RestletRoutable {
    @Override
    public Router getRestlet(Context context) {
        Router router = new Router(context);
        router.attach("/push", ResourcePushResource.class); // URL final: /wm/resources/push
        return router;
    }

    @Override
    public String basePath() {
        return "/wm/resources";
    }
}