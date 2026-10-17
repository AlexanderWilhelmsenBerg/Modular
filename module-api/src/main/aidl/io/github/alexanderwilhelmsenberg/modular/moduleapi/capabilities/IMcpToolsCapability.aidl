package io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities;

/** JSON descriptors/results are bounded and parsed as data by the Core broker.
 * Advertising this capability never exposes a remote tool without user policy.
 */
interface IMcpToolsCapability {
    String listTools() = 1;
    String callTool(String instanceId, String name, String argumentsJson) = 2;
}
