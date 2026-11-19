package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import android.view.SurfaceControlViewHost.SurfacePackage;

interface IWorkspaceSurfaceSession {
    SurfacePackage getSurfacePackage();
    void updateHost(String hostJson);
    boolean dispatchBack();
    void close();
}
