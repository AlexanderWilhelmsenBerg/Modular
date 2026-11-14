package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

import android.os.IBinder;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceHost;
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IWorkspaceSurfaceSession;

interface IWorkspaceSurfaceCapability {
    IWorkspaceSurfaceSession createSession(IBinder hostToken, String hostJson, IWorkspaceSurfaceHost host);
}
