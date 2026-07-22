package io.github.shigella520.mindtrain.mcp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class McpCompatibility {
    public static final String PLUGIN_VERSION_HEADER = "X-MindTrain-Plugin-Version";
    public static final String CONTRACT_VERSION_HEADER = "X-MindTrain-Contract-Version";
    public static final int CONTRACT_VERSION = 1;
    public static final String MINIMUM_PLUGIN_VERSION = "0.2.0";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*$");

    private final String serverVersion;

    public McpCompatibility(BuildProperties buildProperties) {
        this.serverVersion = buildProperties.getVersion();
    }

    public String serverVersion() {
        return serverVersion;
    }

    public void requireCompatibleToolClient(String pluginVersion, String contractVersion) {
        if (pluginVersion == null || pluginVersion.isBlank() || contractVersion == null || contractVersion.isBlank()) {
            incompatible("当前 Codex Plugin 未提供兼容性信息");
        }
        int contract;
        try {
            contract = Integer.parseInt(contractVersion);
        } catch (NumberFormatException exception) {
            incompatible("Codex Plugin 契约版本无效");
            return;
        }
        if (contract != CONTRACT_VERSION) {
            incompatible("Codex Plugin 契约版本 " + contract + " 与服务端契约版本 " + CONTRACT_VERSION + " 不兼容");
        }
        if (compareVersions(pluginVersion, MINIMUM_PLUGIN_VERSION) < 0) {
            incompatible("Codex Plugin 版本 " + pluginVersion + " 低于服务端要求的最低版本 " + MINIMUM_PLUGIN_VERSION);
        }
    }

    public void requireCompatibleInitializeClient(String clientName, String clientVersion) {
        if ("mindtrain-plugin".equals(clientName)
            && compareVersions(clientVersion, MINIMUM_PLUGIN_VERSION) < 0) {
            incompatible("Codex Plugin 版本 " + clientVersion + " 低于服务端要求的最低版本 " + MINIMUM_PLUGIN_VERSION);
        }
    }

    private static int compareVersions(String left, String right) {
        int[] leftParts = parseVersion(left);
        int[] rightParts = parseVersion(right);
        for (int index = 0; index < leftParts.length; index++) {
            int comparison = Integer.compare(leftParts[index], rightParts[index]);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static int[] parseVersion(String value) {
        Matcher matcher = VERSION_PATTERN.matcher(value == null ? "" : value);
        if (!matcher.matches()) return new int[]{-1, -1, -1};
        return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))};
    }

    private static void incompatible(String reason) {
        throw new IncompatiblePluginException(reason + "。请在 Codex 中更新或重新安装 MindTrain Plugin，并开启新任务；若 Plugin 更新，请同步升级服务端。");
    }

    public static class IncompatiblePluginException extends RuntimeException {
        public IncompatiblePluginException(String message) {
            super(message);
        }
    }
}
