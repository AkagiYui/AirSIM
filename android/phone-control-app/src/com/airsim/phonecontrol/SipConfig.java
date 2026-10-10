package com.airsim.phonecontrol;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * SIP gateway settings, read from {@code <external files>/sip.properties}
 * (/sdcard/Android/data/&lt;package&gt;/files/sip.properties) so they can be provisioned with adb.
 */
final class SipConfig {
    static final String FILE_NAME = "sip.properties";

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

    static File file(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    static long stamp(Context context) {
        File file = file(context);
        return file == null || !file.isFile() ? 0L : file.lastModified() ^ file.length();
    }

    static SipConfig load(Context context) {
        File file = file(context);
        Properties values = new Properties();
        if (file != null && file.isFile()) {
            try (InputStream input = new FileInputStream(file)) {
                values.load(input);
            } catch (IOException error) {
                BridgeLog.error("sip_config_read_failed", error);
            }
        }
        String host = values.getProperty("server", "").trim();
        String username = values.getProperty("username", "").trim();
        boolean enabled = Boolean.parseBoolean(values.getProperty("enabled", "false").trim())
                && !host.isEmpty() && !username.isEmpty();
        int port;
        try {
            port = Integer.parseInt(values.getProperty("port", "5060").trim());
        } catch (NumberFormatException error) {
            port = 5060;
        }
        return new SipConfig(enabled, host, port, username, values.getProperty("password", ""),
                values.getProperty("inbound_target", "inbound").trim(), stamp(context));
    }
}
