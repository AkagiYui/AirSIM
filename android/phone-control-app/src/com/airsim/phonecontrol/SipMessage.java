package com.airsim.phonecontrol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal SIP message model: start line, ordered headers and an optional body (RFC 3261). */
final class SipMessage {
    final boolean request;
    final String method;
    final String uri;
    final int status;
    final String reason;
    final List<String[]> headers = new ArrayList<>();
    String body = "";

    private SipMessage(boolean request, String method, String uri, int status, String reason) {
        this.request = request;
        this.method = method;
        this.uri = uri;
        this.status = status;
        this.reason = reason;
    }

    static SipMessage request(String method, String uri) {
        return new SipMessage(true, method, uri, 0, "");
    }

    static SipMessage response(int status, String reason) {
        return new SipMessage(false, "", "", status, reason);
    }

    static SipMessage parse(byte[] data, int length) {
        String text = new String(data, 0, length, StandardCharsets.UTF_8);
        int split = text.indexOf("\r\n\r\n");
        String head = split < 0 ? text : text.substring(0, split);
        String body = split < 0 ? "" : text.substring(split + 4);
        String[] lines = head.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) throw new IllegalArgumentException("empty SIP message");
        String[] start = lines[0].split(" ", 3);
        if (start.length < 3) throw new IllegalArgumentException("bad SIP start line");
        SipMessage message = start[0].startsWith("SIP/")
                ? response(Integer.parseInt(start[1]), start[2])
                : request(start[0], start[1]);
        String[] last = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ((line.startsWith(" ") || line.startsWith("\t")) && last != null) {
                last[1] = last[1] + " " + line.trim();
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            last = new String[] {canonicalName(line.substring(0, colon).trim()), line.substring(colon + 1).trim()};
            message.headers.add(last);
        }
        String declared = message.header("Content-Length");
        if (!declared.isEmpty()) {
            try {
                int count = Integer.parseInt(declared.trim());
                byte[] raw = body.getBytes(StandardCharsets.UTF_8);
                if (count < raw.length) body = new String(raw, 0, count, StandardCharsets.UTF_8);
            } catch (NumberFormatException ignored) {
            }
        }
        message.body = body;
        return message;
    }

    private static String canonicalName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "i", "call-id" -> "Call-ID";
            case "f", "from" -> "From";
            case "t", "to" -> "To";
            case "v", "via" -> "Via";
            case "m", "contact" -> "Contact";
            case "l", "content-length" -> "Content-Length";
            case "c", "content-type" -> "Content-Type";
            case "cseq" -> "CSeq";
            default -> name;
        };
    }

    SipMessage add(String name, String value) {
        headers.add(new String[] {name, value});
        return this;
    }

    String header(String name) {
        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase(name)) return header[1];
        }
        return "";
    }

    List<String> all(String name) {
        List<String> values = new ArrayList<>();
        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase(name)) values.add(header[1]);
        }
        return values;
    }

    int cseqNumber() {
        String[] parts = header("CSeq").trim().split("\\s+");
        try { return Integer.parseInt(parts[0]); }
        catch (RuntimeException error) { return 0; }
    }

    String cseqMethod() {
        String[] parts = header("CSeq").trim().split("\\s+");
        return parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
    }

    String topBranch() {
        return param(header("Via"), "branch");
    }

    byte[] encode() {
        StringBuilder out = new StringBuilder();
        out.append(request ? method + " " + uri + " SIP/2.0" : "SIP/2.0 " + status + " " + reason).append("\r\n");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase("Content-Length")) continue;
            out.append(header[0]).append(": ").append(header[1]).append("\r\n");
        }
        out.append("Content-Length: ").append(payload.length).append("\r\n\r\n");
        byte[] head = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + payload.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(payload, 0, all, head.length, payload.length);
        return all;
    }

    /** Returns a ";name=value" parameter of a header value, or "". */
    static String param(String value, String name) {
        for (String part : value.split(";")) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && trimmed.substring(0, equals).trim().equalsIgnoreCase(name)) {
                return trimmed.substring(equals + 1).trim();
            }
        }
        return "";
    }

    /** Extracts the URI from a name-addr ("Name" &lt;sip:x@y&gt;;tag=…) or addr-spec. */
    static String uriOf(String nameAddr) {
        int open = nameAddr.indexOf('<');
        int close = nameAddr.indexOf('>', open + 1);
        if (open >= 0 && close > open) return nameAddr.substring(open + 1, close);
        int semicolon = nameAddr.indexOf(';');
        return (semicolon < 0 ? nameAddr : nameAddr.substring(0, semicolon)).trim();
    }

    /** Returns the user part of a sip: URI ("sip:10000@host;x" → "10000"). */
    static String userOf(String uri) {
        String value = uri.startsWith("sip:") ? uri.substring(4) : uri.startsWith("sips:") ? uri.substring(5) : uri;
        int at = value.indexOf('@');
        return at < 0 ? "" : value.substring(0, at);
    }

    /** Reads one parameter (quoted or not) from a Digest challenge. */
    static String challengeParam(String challenge, String name) {
        String lower = challenge.toLowerCase(Locale.ROOT);
        int index = 0;
        while ((index = lower.indexOf(name.toLowerCase(Locale.ROOT) + "=", index)) >= 0) {
            boolean boundary = index == 0 || !Character.isLetterOrDigit(lower.charAt(index - 1));
            int start = index + name.length() + 1;
            if (!boundary) { index = start; continue; }
            if (start < challenge.length() && challenge.charAt(start) == '"') {
                int end = challenge.indexOf('"', start + 1);
                return end < 0 ? challenge.substring(start + 1) : challenge.substring(start + 1, end);
            }
            int end = start;
            while (end < challenge.length() && challenge.charAt(end) != ',') end++;
            return challenge.substring(start, end).trim();
        }
        return "";
    }

    /** Builds a Digest (MD5) Authorization header value answering {@code challenge}. */
    static String digestAuthorization(String challenge, String method, String uri,
            String username, String password, String cnonce) {
        String realm = challengeParam(challenge, "realm");
        String nonce = challengeParam(challenge, "nonce");
        String opaque = challengeParam(challenge, "opaque");
        String qop = challengeParam(challenge, "qop");
        boolean auth = false;
        for (String option : qop.split(",")) if (option.trim().equalsIgnoreCase("auth")) auth = true;
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String nc = "00000001";
        String response = auth ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);
        StringBuilder value = new StringBuilder("Digest username=\"").append(username)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri)
                .append("\", response=\"").append(response)
                .append("\", algorithm=MD5");
        if (!opaque.isEmpty()) value.append(", opaque=\"").append(opaque).append('"');
        if (auth) value.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        return value.toString();
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
