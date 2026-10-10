package com.airsim.phonecontrol;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * SIP gateway settings, stored in private SharedPreferences and edited from the Settings page.
 * A {@code sip.properties} file pushed by adb into the app's external files directory is imported
 * once and then deleted, so the password does not stay in shared storage.
 */
final class SipConfig {
    static final String IMPORT_FILE_NAME = "sip.properties";
    static final String DEFAULT_INBOUND_TARGET = "inbound";
    private static final String FILE = "sip";

    final boolean enabled;
    final String host;
    final int port;
    final String username;
    final String password;
    final String inboundTarget;
    final long stamp;

    private SipConfig(boolean enabled, String host, int port, String username, String password,
            String inboundTarget, long stamp) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.inboundTarget = inboundTarget;
        this.stamp = stamp;
    }

    /** Whether the settings are complete enough to register; {@link #enabled} also requires the switch. */
    boolean complete() {
        return !host.isEmpty() && !username.isEmpty() && port > 0 && port <= 65535;
    }

    boolean hasPassword() {
        return !password.isEmpty();
    }

    static SipConfig load(Context context) {
        importProvisionedFile(context);
        SharedPreferences values = preferences(context);
        String host = values.getString("server", "");
        String username = values.getString("username", "");
        int port = values.getInt("port", 5060);
        boolean complete = !host.isEmpty() && !username.isEmpty() && port > 0 && port <= 65535;
        return new SipConfig(values.getBoolean("enabled", false) && complete, host, port, username,
                values.getString("password", ""),
                values.getString("inbound_target", DEFAULT_INBOUND_TARGET),
                values.getLong("version", 0L));
    }

    static long stamp(Context context) {
        importProvisionedFile(context);
        return preferences(context).getLong("version", 0L);
    }

    /**
     * Saves the settings. An empty {@code password} keeps the stored one, so the UI never has to
     * show it again. Throws {@link IllegalArgumentException} with a user-facing message.
     */
    static void save(Context context, boolean enabled, String host, String port, String username,
            String password, String inboundTarget) {
        String cleanHost = host == null ? "" : host.trim();
        String cleanUser = username == null ? "" : username.trim();
        String cleanTarget = inboundTarget == null || inboundTarget.trim().isEmpty()
                ? DEFAULT_INBOUND_TARGET : inboundTarget.trim();
        int parsedPort;
        try {
            parsedPort = port == null || port.trim().isEmpty() ? 5060 : Integer.parseInt(port.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("端口必须是数字");
        }
        if (parsedPort <= 0 || parsedPort > 65535) throw new IllegalArgumentException("端口范围应为 1–65535");
        if (enabled && cleanHost.isEmpty()) throw new IllegalArgumentException("请填写 SIP 服务器地址");
        if (enabled && cleanUser.isEmpty()) throw new IllegalArgumentException("请填写 SIP 用户名");
        if (cleanHost.contains(" ") || cleanUser.contains(" ") || cleanTarget.contains(" ")) {
            throw new IllegalArgumentException("服务器、用户名和来电目标不能包含空格");
        }
        SharedPreferences values = preferences(context);
        SharedPreferences.Editor editor = values.edit()
                .putBoolean("enabled", enabled)
                .putString("server", cleanHost)
                .putInt("port", parsedPort)
                .putString("username", cleanUser)
                .putString("inbound_target", cleanTarget)
                .putLong("version", values.getLong("version", 0L) + 1);
        if (password != null && !password.isEmpty()) editor.putString("password", password);
        editor.apply();
    }

    private static void importProvisionedFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, IMPORT_FILE_NAME);
        if (file == null || !file.isFile()) return;
        Properties values = new Properties();
        try (InputStream input = new FileInputStream(file)) {
            values.load(input);
        } catch (IOException error) {
            BridgeLog.error("sip_config_import_failed", error);
            return;
        }
        try {
            save(context,
                    Boolean.parseBoolean(values.getProperty("enabled", "false").trim()),
                    values.getProperty("server", ""),
                    values.getProperty("port", "5060"),
                    values.getProperty("username", ""),
                    values.getProperty("password", ""),
                    values.getProperty("inbound_target", DEFAULT_INBOUND_TARGET));
            BridgeLog.info("sip_config_imported source=" + IMPORT_FILE_NAME);
        } catch (IllegalArgumentException error) {
            BridgeLog.info("sip_config_import_rejected reason=" + error.getMessage());
        }
        if (!file.delete()) BridgeLog.info("sip_config_import_file_not_deleted");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
