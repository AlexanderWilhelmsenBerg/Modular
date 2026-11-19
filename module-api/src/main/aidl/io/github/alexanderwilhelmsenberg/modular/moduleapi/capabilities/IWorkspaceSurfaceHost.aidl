package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

oneway interface IWorkspaceSurfaceHost {
    void onStateChanged(String sessionId, String stateJson);
    void requestNavigation(String sessionId, String destination);
    void requestActivity(String sessionId, String nonce);
}
