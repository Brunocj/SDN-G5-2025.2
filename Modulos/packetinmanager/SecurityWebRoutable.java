package net.floodlightcontroller.packetinmanager;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.restserver.RestletRoutable;

public class SecurityWebRoutable implements RestletRoutable {

    @Override
    public String basePath() {
        // Base: http://controller:8080/wm/security/...
        return "/wm/security";
    }

    @Override
    public Restlet getRestlet(Context context) {
        Router router = new Router(context);

        // GET  /wm/security/posibles?ip=...
        router.attach("/obtenerPosibleSesion", PossibleSessionResource.class);

        // POST /wm/security/sesiones_activas
        router.attach("/crearSesionActiva", CreateActiveSessionResource.class);

	    router.attach("/borrarSesionActiva", DeleteActiveSessionResource.class);

        router.attach("/bloquearIP", BlockIpResource.class);

        return router;
    }
}
