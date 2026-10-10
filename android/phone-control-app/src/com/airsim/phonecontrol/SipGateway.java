package com.airsim.phonecontrol;

import android.content.Context;
import android.telecom.TelecomManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Standard SIP (UDP, RFC 3261) user agent that turns the phone into a single-line GSM gateway.
 *
 * <ul>
 * <li>Registers to the configured server as {@code username}.</li>
 * <li>Cellular call ringing → INVITE {@code sip:<inbound_target>@server} with the caller in
 *     {@code X-AirSIM-Caller}; 200 OK → answer the cellular call and bridge audio.</li>
 * <li>INVITE from the server ({@code sip:<number>@gateway}) → place a cellular call; 180 while it
 *     dials, 200 OK once it is active.</li>
 * <li>BYE/CANCEL/hang-up are mirrored both ways; RFC 4733 DTMF is played into the cellular call.</li>
 * </ul>
 *
 * All protocol state lives on the single {@link #loop} thread; blocking Telecom work runs on {@link #telecom}.
 */
public final class SipGateway {
    private static final String USER_AGENT = "AirSIM-SIP/0.1";
    private static final int REGISTER_EXPIRES = 120;
    private static final long REGISTER_REFRESH_MS = 50_000L;
    private static volatile SipGateway instance;

    private final Context context;
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService telecom = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, ScheduledFuture<?>> retransmits = new HashMap<>();

    private SipConfig config;
    private DatagramSocket socket;
    private InetSocketAddress server;
    private String localIp = "";
    private boolean started;

    private final String registerCallId = token(16);
    private final String registerTag = token(8);
    private int registerCseq = 1;
    private boolean registered;
    private long lastRegisterAt;
    private long lastRegisterResponseAt;
    private boolean registerAuthTried;
    private int registerExpires = REGISTER_EXPIRES;
    private long unregisterDeadline;
    private String failure = "";

    private Leg leg;
    private Leg endedInbound;

    private volatile Status status = new Status(Status.DISABLED, "", "", "", Status.CALL_IDLE);
    private volatile Runnable listener;

    /** Immutable snapshot of the gateway for the UI. */
    public static final class Status {
        public static final String DISABLED = "disabled";
        public static final String CONNECTING = "connecting";
        public static final String REGISTERED = "registered";
        public static final String FAILED = "failed";
        public static final String CALL_IDLE = "idle";
        public static final String CALL_INBOUND_RINGING = "inbound_ringing";
        public static final String CALL_OUTBOUND_DIALING = "outbound_dialing";
        public static final String CALL_ACTIVE = "active";

        public final String state;
        public final String detail;
        public final String server;
        public final String localAddress;
        public final String call;

        Status(String state, String detail, String server, String localAddress, String call) {
            this.state = state;
            this.detail = detail;
            this.server = server;
            this.localAddress = localAddress;
            this.call = call;
        }

        boolean sameAs(Status other) {
            return state.equals(other.state) && detail.equals(other.detail) && server.equals(other.server)
                    && localAddress.equals(other.localAddress) && call.equals(other.call);
        }
    }

    private SipGateway(Context context) {
        this.context = context.getApplicationContext();
    }

    public static SipGateway get(Context context) {
        SipGateway current = instance;
        if (current != null) return current;
        synchronized (SipGateway.class) {
            if (instance == null) instance = new SipGateway(context);
            return instance;
        }
    }

    public void start() {
        loop.execute(() -> {
            if (started) return;
            started = true;
            loop.scheduleWithFixedDelay(this::maintain, 0, 10, TimeUnit.SECONDS);
        });
    }

    /** Called by the InCallService for every de-duplicated Telecom state change. */
    public void onTelecomState(String callId, String direction, String state, String number) {
        loop.execute(() -> {
            handleTelecom(callId, direction, state, number);
            publish();
        });
    }

    /** Applies saved settings now instead of on the next maintenance tick. */
    public void reload() {
        loop.execute(this::maintain);
    }

    public Status status() {
        return status;
    }

    /** Receives a callback (on a background thread) whenever {@link #status()} changes. */
    public void setListener(Runnable value) {
        listener = value;
    }

    // ---------------------------------------------------------------- registration / socket

    private void maintain() {
        try {
            long tick = System.currentTimeMillis();
            if (unregisterDeadline > 0) {
                if (tick < unregisterDeadline) return;
                unregisterDeadline = 0;
                registerExpires = REGISTER_EXPIRES;
                closeSocket();
            }
            if ((config == null || config.stamp != SipConfig.stamp(context)) && leg == null) {
                if (config != null && config.enabled && registered && socket != null) {
                    unregister();
                    return;
                }
                config = SipConfig.load(context);
                closeSocket();
                registered = false;
                failure = "";
                lastRegisterAt = 0;
                BridgeLog.info("sip_config_loaded enabled=" + config.enabled
                        + (config.enabled ? " server=" + config.host + ":" + config.port + " user=" + config.username : ""));
            }
            if (!config.enabled) return;
            String currentIp = probeLocalIp();
            if (socket == null || !currentIp.equals(localIp)) {
                closeSocket();
                openSocket(currentIp);
                registered = false;
                lastRegisterAt = 0;
            }
            long now = System.currentTimeMillis();
            if (!registered && lastRegisterAt > 0 && lastRegisterResponseAt < lastRegisterAt
                    && now - lastRegisterAt > 8_000L) {
                failure = "服务器无响应";
            }
            if (now - lastRegisterAt >= (registered ? REGISTER_REFRESH_MS : 15_000L)) {
                registerAuthTried = false;
                registerExpires = REGISTER_EXPIRES;
                sendRegister("");
            }
        } catch (Exception error) {
            BridgeLog.error("sip_maintain_failed", error);
            failure = "网络错误：" + error.getClass().getSimpleName();
            registered = false;
            closeSocket();
        } finally {
            publish();
        }
    }

    /**
     * Removes the current registration (Expires: 0) with the old settings, then lets the next
     * maintenance pass close the socket and apply the new settings.
     */
    private void unregister() {
        registered = false;
        registerAuthTried = false;
        registerExpires = 0;
        sendRegister("");
        unregisterDeadline = System.currentTimeMillis() + 1_500L;
        loop.schedule(this::maintain, 1_600L, TimeUnit.MILLISECONDS);
        BridgeLog.info("sip_unregister_requested");
    }

    private void publish() {
        String state;
        if (config == null || !config.enabled) state = Status.DISABLED;
        else if (registered) state = Status.REGISTERED;
        else if (!failure.isEmpty()) state = Status.FAILED;
        else state = Status.CONNECTING;
        Leg active = leg;
        String call = active == null ? Status.CALL_IDLE
                : active.media != null ? Status.CALL_ACTIVE
                : active.gsmInbound ? Status.CALL_INBOUND_RINGING : Status.CALL_OUTBOUND_DIALING;
        String server = config == null || config.host.isEmpty() ? "" : config.username + "@" + config.host + ":" + config.port;
        String local = socket == null ? "" : localIp + ":" + localPort();
        Status next = new Status(state, Status.FAILED.equals(state) ? failure : "", server, local, call);
        if (next.sameAs(status)) return;
        status = next;
        Runnable callback = listener;
        if (callback != null) callback.run();
    }

    private String probeLocalIp() throws IOException {
        InetSocketAddress target = new InetSocketAddress(InetAddress.getByName(config.host), config.port);
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(target);
            server = target;
            return probe.getLocalAddress().getHostAddress();
        }
    }

    private void openSocket(String ip) throws IOException {
        DatagramSocket created = new DatagramSocket(new InetSocketAddress(0));
        socket = created;
        localIp = ip;
        Thread receiver = new Thread(() -> receiveLoop(created), "airsim-sip-receiver");
        receiver.setDaemon(true);
        receiver.start();
        BridgeLog.info("sip_socket_open local=" + localIp + ":" + created.getLocalPort());
    }

    private void closeSocket() {
        DatagramSocket active = socket;
        socket = null;
        if (active != null) active.close();
    }

    private void receiveLoop(DatagramSocket active) {
        byte[] buffer = new byte[8192];
        while (!active.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                active.receive(packet);
                if (packet.getLength() < 16) continue; // keep-alive CRLFs
                SipMessage message = SipMessage.parse(packet.getData(), packet.getLength());
                SocketAddress source = packet.getSocketAddress();
                loop.execute(() -> {
                    handleMessage(message, source);
                    publish();
                });
            } catch (IOException error) {
                if (!active.isClosed()) BridgeLog.error("sip_receive_failed", error);
            } catch (RuntimeException error) {
                BridgeLog.error("sip_parse_failed", error);
            }
        }
    }

    private void sendRegister(String authorization) {
        String uri = "sip:" + config.host;
        SipMessage request = SipMessage.request("REGISTER", uri);
        addVia(request, newBranch());
        request.add("Max-Forwards", "70")
                .add("From", "<sip:" + config.username + "@" + config.host + ">;tag=" + registerTag)
                .add("To", "<sip:" + config.username + "@" + config.host + ">")
                .add("Call-ID", registerCallId)
                .add("CSeq", (registerCseq++) + " REGISTER")
                .add("Contact", contact())
                .add("Expires", Integer.toString(registerExpires))
                .add("User-Agent", USER_AGENT);
        if (!authorization.isEmpty()) request.add("Authorization", authorization);
        lastRegisterAt = System.currentTimeMillis();
        sendReliable(request);
    }

    private void onRegisterResponse(SipMessage response) {
        lastRegisterResponseAt = System.currentTimeMillis();
        if (registerExpires == 0) {
            // Answer the challenge of an un-REGISTER, but never treat it as a registration.
            if ((response.status == 401 || response.status == 407) && !registerAuthTried) {
                registerAuthTried = true;
                String challenge = response.header(response.status == 401 ? "WWW-Authenticate" : "Proxy-Authenticate");
                sendRegister(SipMessage.digestAuthorization(challenge, "REGISTER", "sip:" + config.host,
                        config.username, config.password, token(8)));
            } else if (response.status >= 200 && response.status < 300) {
                BridgeLog.info("sip_unregistered");
            }
            return;
        }
        if (response.status == 401 || response.status == 407) {
            if (registerAuthTried) {
                BridgeLog.info("sip_register_rejected status=" + response.status + " reason=credentials");
                registered = false;
                failure = "认证失败：用户名或密码错误（" + response.status + "）";
                return;
            }
            registerAuthTried = true;
            String challenge = response.header(response.status == 401 ? "WWW-Authenticate" : "Proxy-Authenticate");
            sendRegister(SipMessage.digestAuthorization(challenge, "REGISTER", "sip:" + config.host,
                    config.username, config.password, token(8)));
        } else if (response.status >= 200 && response.status < 300) {
            if (!registered) BridgeLog.info("sip_registered server=" + config.host + ":" + config.port);
            registered = true;
            failure = "";
        } else if (response.status >= 300) {
            registered = false;
            failure = "服务器拒绝注册（" + response.status + " " + response.reason + "）";
            BridgeLog.info("sip_register_failed status=" + response.status);
        }
    }

    // ---------------------------------------------------------------- message dispatch

    private void handleMessage(SipMessage message, SocketAddress source) {
        if (!message.request) {
            cancelRetransmit(message.topBranch());
            switch (message.cseqMethod()) {
                case "REGISTER" -> onRegisterResponse(message);
                case "INVITE" -> onInviteResponse(message);
                default -> { }
            }
            return;
        }
        switch (message.method.toUpperCase(Locale.ROOT)) {
            case "INVITE" -> onInvite(message, source);
            case "ACK" -> onAck(message);
            case "BYE" -> onBye(message, source);
            case "CANCEL" -> onCancel(message, source);
            case "OPTIONS", "NOTIFY", "INFO" -> reply(message, source, 200, "OK", null);
            default -> reply(message, source, 405, "Method Not Allowed", null);
        }
    }

    // ---------------------------------------------------------------- cellular → SIP

    private void handleTelecom(String callId, String direction, String state, String number) {
        Leg active = leg;
        if (active == null) {
            if ("incoming".equals(direction) && "incoming".equals(state)
                    && config != null && config.enabled && registered && socket != null) {
                startGsmInbound(callId, number);
            }
            return;
        }
        if (active.telecomCallId.isEmpty() && !active.gsmInbound && "outgoing".equals(direction)) {
            active.telecomCallId = callId;
        }
        if (!callId.equals(active.telecomCallId)) return;
        switch (state) {
            case "dialing" -> {
                if (!active.gsmInbound && !active.ringingSent) {
                    active.ringingSent = true;
                    reply(active.invite, active.peer, 180, "Ringing", null);
                }
            }
            case "active" -> {
                active.telecomActive = true;
                if (!active.gsmInbound && !active.finalSent) {
                    active.finalSent = true;
                    sendInviteOk(active);
                }
                startMedia(active);
            }
            case "ended" -> {
                if (active.gsmInbound) {
                    if (active.confirmed) sendBye(active);
                    else sendCancel(active);
                } else if (!active.finalSent) {
                    active.finalSent = true;
                    reply(active.invite, active.peer, 480, "Temporarily Unavailable", null);
                } else {
                    sendBye(active);
                }
                teardown(active, "telecom_ended");
            }
            default -> { }
        }
    }

    private void startGsmInbound(String telecomCallId, String number) {
        Leg created;
        try {
            created = new Leg(true, token(16) + "@airsim", token(8));
        } catch (IOException error) {
            BridgeLog.error("sip_rtp_bind_failed", error);
            return;
        }
        created.telecomCallId = telecomCallId;
        created.peer = server;
        String target = "sip:" + config.inboundTarget + "@" + config.host;
        String caller = number == null ? "" : number.replaceAll("[^0-9+*#]", "");
        created.localParty = "\"" + (caller.isEmpty() ? "unknown" : caller) + "\" <sip:" + config.username
                + "@" + config.host + ">;tag=" + created.localTag;
        created.remoteParty = "<" + target + ">";
        created.remoteTarget = target;
        created.caller = caller;
        leg = created;
        sendInvite(created, "", "");
        BridgeLog.info("sip_gsm_inbound_invite call_id=" + DebugRedactor.safeIdentifier(telecomCallId));
    }

    private void sendInvite(Leg active, String authHeader, String authorization) {
        SipMessage invite = SipMessage.request("INVITE", active.remoteTarget);
        active.inviteBranch = newBranch();
        active.inviteCseq = active.localCseq++;
        addVia(invite, active.inviteBranch);
        invite.add("Max-Forwards", "70")
                .add("From", active.localParty)
                .add("To", active.remoteParty)
                .add("Call-ID", active.sipCallId)
                .add("CSeq", active.inviteCseq + " INVITE")
                .add("Contact", contact())
                .add("User-Agent", USER_AGENT)
                .add("Allow", "INVITE, ACK, BYE, CANCEL, OPTIONS")
                .add("X-AirSIM-Caller", active.caller.isEmpty() ? "unknown" : active.caller)
                .add("Content-Type", "application/sdp");
        if (!authorization.isEmpty()) invite.add(authHeader, authorization);
        invite.body = sdp(active, null);
        active.sentInvite = invite;
        sendReliable(invite);
    }

    private void onInviteResponse(SipMessage response) {
        Leg active = leg;
        if (active == null || !active.gsmInbound || !active.sipCallId.equals(response.header("Call-ID"))) {
            // e.g. the 487 that follows our CANCEL after the leg is gone: it still needs an ACK.
            Leg ended = endedInbound;
            if (ended != null && response.status >= 300 && ended.sipCallId.equals(response.header("Call-ID"))) {
                sendNon2xxAck(ended, response);
            }
            return;
        }
        if (response.status < 200) return;
        if (response.status == 401 || response.status == 407) {
            sendNon2xxAck(active, response);
            if (active.authTried) {
                BridgeLog.info("sip_invite_rejected status=" + response.status + " reason=credentials");
                rejectTelecom(active);
                teardown(active, "auth_failed");
                return;
            }
            active.authTried = true;
            boolean proxy = response.status == 407;
            String challenge = response.header(proxy ? "Proxy-Authenticate" : "WWW-Authenticate");
            sendInvite(active, proxy ? "Proxy-Authorization" : "Authorization",
                    SipMessage.digestAuthorization(challenge, "INVITE", active.remoteTarget,
                            config.username, config.password, token(8)));
            return;
        }
        if (response.status >= 300) {
            sendNon2xxAck(active, response);
            BridgeLog.info("sip_gsm_inbound_unanswered status=" + response.status);
            rejectTelecom(active);
            teardown(active, "sip_" + response.status);
            return;
        }
        // 2xx: confirm dialog, ACK, then answer the cellular call.
        String to = response.header("To");
        active.remoteTag = SipMessage.param(to, "tag");
        active.remoteParty = to;
        String contact = response.header("Contact");
        if (!contact.isEmpty()) active.remoteTarget = SipMessage.uriOf(contact);
        sendAck(active, response.cseqNumber());
        if (active.confirmed) return; // 200 OK retransmission
        active.confirmed = true;
        active.remoteSdp = response.body;
        BridgeLog.info("sip_gsm_inbound_answered_by_sip");
        String telecomCallId = active.telecomCallId;
        telecom.execute(() -> {
            CallRepository.ActionResult result = CallRepository.execute(
                    new AgentCommand("sip-" + token(6), "answer", telecomCallId, ""), telecomManager());
            if (!result.success) BridgeLog.info("sip_telecom_answer_failed error=" + result.error);
        });
    }

    // ---------------------------------------------------------------- SIP → cellular

    private void onInvite(SipMessage invite, SocketAddress source) {
        Leg active = leg;
        String sipCallId = invite.header("Call-ID");
        if (active != null && active.sipCallId.equals(sipCallId)) {
            if (active.invite != null && invite.cseqNumber() == active.invite.cseqNumber()) {
                // Retransmission of the initial INVITE.
                if (active.lastFinal != null) send(active.lastFinal, source);
                else reply(invite, source, 100, "Trying", null);
            } else {
                // re-INVITE (session refresh): keep the existing media and answer with our SDP.
                SipMessage ok = buildResponse(invite, 200, "OK", active.localTag);
                ok.add("Contact", contact()).add("Content-Type", "application/sdp");
                ok.body = sdp(active, invite.body.isEmpty() ? active.remoteSdp : invite.body);
                send(ok, source);
            }
            return;
        }
        if (active != null || config == null || !config.enabled) {
            reply(invite, source, 486, "Busy Here", null);
            return;
        }
        String number = SipMessage.userOf(invite.uri).replaceAll("[^0-9+*#]", "");
        if (number.isEmpty()) {
            reply(invite, source, 404, "Not Found", null);
            return;
        }
        Leg created;
        try {
            created = new Leg(false, sipCallId, token(8));
        } catch (IOException error) {
            BridgeLog.error("sip_rtp_bind_failed", error);
            reply(invite, source, 500, "Server Internal Error", null);
            return;
        }
        created.invite = invite;
        created.peer = source;
        created.remoteSdp = invite.body;
        String from = invite.header("From");
        created.remoteTag = SipMessage.param(from, "tag");
        created.remoteParty = from;
        created.localParty = invite.header("To") + ";tag=" + created.localTag;
        String contact = invite.header("Contact");
        created.remoteTarget = contact.isEmpty() ? SipMessage.uriOf(from) : SipMessage.uriOf(contact);
        leg = created;
        reply(invite, source, 100, "Trying", null);
        BridgeLog.info("sip_outbound_dial_requested");
        telecom.execute(() -> {
            CallRepository.ActionResult result = CallRepository.execute(
                    new AgentCommand("sip-" + token(6), "dial", "", number), telecomManager());
            if (!result.success) {
                loop.execute(() -> {
                    if (leg != created || created.finalSent) return;
                    created.finalSent = true;
                    BridgeLog.info("sip_telecom_dial_failed error=" + result.error);
                    reply(created.invite, created.peer, 503, "Service Unavailable", null);
                    teardown(created, "dial_failed");
                });
            }
        });
    }

    private void sendInviteOk(Leg active) {
        SipMessage ok = buildResponse(active.invite, 200, "OK", active.localTag);
        ok.add("Contact", contact()).add("Content-Type", "application/sdp");
        ok.body = sdp(active, active.remoteSdp);
        active.lastFinal = ok;
        send(ok, active.peer);
        scheduleOkRetransmit(active, ok, 500, 0);
        BridgeLog.info("sip_outbound_answered");
    }

    private void scheduleOkRetransmit(Leg active, SipMessage ok, long delay, int attempt) {
        if (attempt >= 10) return;
        active.okRetransmit = loop.schedule(() -> {
            if (leg != active || active.confirmed) return;
            send(ok, active.peer);
            scheduleOkRetransmit(active, ok, Math.min(delay * 2, 4_000), attempt + 1);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void onAck(SipMessage ack) {
        Leg active = leg;
        if (active == null || active.gsmInbound || !active.sipCallId.equals(ack.header("Call-ID"))) return;
        active.confirmed = true;
        if (active.okRetransmit != null) active.okRetransmit.cancel(false);
    }

    private void onBye(SipMessage bye, SocketAddress source) {
        Leg active = leg;
        reply(bye, source, 200, "OK", null);
        if (active == null || !active.sipCallId.equals(bye.header("Call-ID"))) return;
        BridgeLog.info("sip_remote_hangup");
        endTelecom(active);
        teardown(active, "remote_bye");
    }

    private void onCancel(SipMessage cancel, SocketAddress source) {
        Leg active = leg;
        reply(cancel, source, 200, "OK", null);
        if (active == null || active.gsmInbound || !active.sipCallId.equals(cancel.header("Call-ID"))) return;
        if (!active.finalSent) {
            active.finalSent = true;
            reply(active.invite, active.peer, 487, "Request Terminated", null);
        }
        BridgeLog.info("sip_remote_cancel");
        endTelecom(active);
        teardown(active, "remote_cancel");
    }

    // ---------------------------------------------------------------- in-dialog requests

    private void sendAck(Leg active, int cseq) {
        SipMessage ack = SipMessage.request("ACK", active.remoteTarget);
        addVia(ack, newBranch());
        ack.add("Max-Forwards", "70")
                .add("From", active.localParty)
                .add("To", active.remoteParty)
                .add("Call-ID", active.sipCallId)
                .add("CSeq", cseq + " ACK")
                .add("User-Agent", USER_AGENT);
        send(ack, server);
    }

    private void sendNon2xxAck(Leg active, SipMessage response) {
        SipMessage ack = SipMessage.request("ACK", active.sentInvite.uri);
        addVia(ack, active.inviteBranch);
        ack.add("Max-Forwards", "70")
                .add("From", active.localParty)
                .add("To", response.header("To"))
                .add("Call-ID", active.sipCallId)
                .add("CSeq", response.cseqNumber() + " ACK");
        send(ack, server);
    }

    private void sendCancel(Leg active) {
        if (active.sentInvite == null) return;
        SipMessage cancel = SipMessage.request("CANCEL", active.sentInvite.uri);
        addVia(cancel, active.inviteBranch);
        cancel.add("Max-Forwards", "70")
                .add("From", active.localParty)
                .add("To", active.remoteParty)
                .add("Call-ID", active.sipCallId)
                .add("CSeq", active.inviteCseq + " CANCEL")
                .add("User-Agent", USER_AGENT);
        send(cancel, server);
    }

    private void sendBye(Leg active) {
        SipMessage bye = SipMessage.request("BYE", active.remoteTarget);
        addVia(bye, newBranch());
        bye.add("Max-Forwards", "70")
                .add("From", active.localParty)
                .add("To", active.remoteParty)
                .add("Call-ID", active.sipCallId)
                .add("CSeq", (active.localCseq++) + " BYE")
                .add("User-Agent", USER_AGENT);
        sendReliable(bye);
    }

    // ---------------------------------------------------------------- media / telecom helpers

    private void startMedia(Leg active) {
        if (active.media != null || !active.telecomActive || active.remoteSdp.isEmpty()) return;
        Sdp remote = Sdp.parse(active.remoteSdp);
        if (remote == null) {
            BridgeLog.info("sip_media_no_remote_sdp");
            return;
        }
        int audio = active.gsmInbound ? remote.firstAudio() : chosenAudio(remote);
        String telecomCallId = active.telecomCallId;
        SipRtpSession session = new SipRtpSession(active.rtpSocket, remote.address, audio, remote.dtmfPayloadType,
                digit -> telecom.execute(() -> CallRepository.execute(
                        new AgentCommand("sip-" + token(6), "dtmf", telecomCallId, String.valueOf(digit)),
                        telecomManager())));
        active.media = session;
        telecom.execute(() -> {
            try {
                session.start();
            } catch (IOException error) {
                BridgeLog.error("sip_media_start_failed", error);
            }
        });
    }

    private void rejectTelecom(Leg active) {
        String telecomCallId = active.telecomCallId;
        telecom.execute(() -> CallRepository.execute(
                new AgentCommand("sip-" + token(6), "reject", telecomCallId, ""), telecomManager()));
    }

    private void endTelecom(Leg active) {
        telecom.execute(() -> {
            String telecomCallId = active.telecomCallId.isEmpty() ? CallRepository.firstId() : active.telecomCallId;
            if (telecomCallId.isEmpty()) return;
            CallRepository.execute(new AgentCommand("sip-" + token(6), "end", telecomCallId, ""), telecomManager());
        });
    }

    private void teardown(Leg active, String reason) {
        if (leg == active) leg = null;
        if (active.gsmInbound) endedInbound = active;
        if (active.okRetransmit != null) active.okRetransmit.cancel(false);
        if (active.media != null) active.media.close();
        else active.rtpSocket.close();
        BridgeLog.info("sip_call_finished reason=" + reason);
    }

    private TelecomManager telecomManager() {
        return context.getSystemService(TelecomManager.class);
    }

    // ---------------------------------------------------------------- SIP plumbing

    private void reply(SipMessage request, SocketAddress destination, int status, String reason, String body) {
        Leg active = leg;
        String toTag = active != null && active.sipCallId.equals(request.header("Call-ID")) ? active.localTag : token(8);
        SipMessage response = buildResponse(request, status, reason, status > 100 ? toTag : "");
        if (body != null) response.body = body;
        if ("INVITE".equalsIgnoreCase(request.method) && status >= 180 && status < 300) response.add("Contact", contact());
        if ("INVITE".equalsIgnoreCase(request.method) && status >= 200 && active != null
                && active.invite == request) {
            active.lastFinal = response;
        }
        send(response, destination);
    }

    private SipMessage buildResponse(SipMessage request, int status, String reason, String toTag) {
        SipMessage response = SipMessage.response(status, reason);
        for (String via : request.all("Via")) response.add("Via", via);
        String to = request.header("To");
        if (!toTag.isEmpty() && SipMessage.param(to, "tag").isEmpty()) to = to + ";tag=" + toTag;
        response.add("From", request.header("From"))
                .add("To", to)
                .add("Call-ID", request.header("Call-ID"))
                .add("CSeq", request.header("CSeq"))
                .add("User-Agent", USER_AGENT);
        return response;
    }

    private void sendReliable(SipMessage request) {
        String branch = request.topBranch();
        send(request, server);
        scheduleRetransmit(branch, request, 500);
    }

    private void scheduleRetransmit(String branch, SipMessage request, long delay) {
        if (delay > 8_000) {
            retransmits.remove(branch);
            return;
        }
        retransmits.put(branch, loop.schedule(() -> {
            if (!retransmits.containsKey(branch)) return;
            send(request, server);
            scheduleRetransmit(branch, request, delay * 2);
        }, delay, TimeUnit.MILLISECONDS));
    }

    private void cancelRetransmit(String branch) {
        ScheduledFuture<?> pending = retransmits.remove(branch);
        if (pending != null) pending.cancel(false);
    }

    private void send(SipMessage message, SocketAddress destination) {
        DatagramSocket active = socket;
        if (active == null || destination == null) return;
        try {
            byte[] bytes = message.encode();
            active.send(new DatagramPacket(bytes, bytes.length, destination));
        } catch (IOException error) {
            BridgeLog.error("sip_send_failed", error);
        }
    }

    private void addVia(SipMessage message, String branch) {
        message.add("Via", "SIP/2.0/UDP " + localIp + ":" + localPort() + ";branch=" + branch + ";rport");
    }

    private String contact() {
        return "<sip:" + (config == null ? "airsim" : config.username) + "@" + localIp + ":" + localPort()
                + ";transport=udp>";
    }

    private int localPort() {
        DatagramSocket active = socket;
        return active == null ? 0 : active.getLocalPort();
    }

    private String newBranch() {
        return "z9hG4bK" + token(10);
    }

    private String token(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        StringBuilder out = new StringBuilder();
        for (byte b : value) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private String sdp(Leg active, String offer) {
        String audioLine;
        StringBuilder attributes = new StringBuilder();
        if (offer == null) {
            audioLine = "0 8 101";
            attributes.append("a=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\n")
                    .append("a=rtpmap:101 telephone-event/8000\r\na=fmtp:101 0-16\r\n");
        } else {
            Sdp remote = Sdp.parse(offer);
            int audio = remote == null ? G711.PCMU : chosenAudio(remote);
            int dtmf = remote == null ? -1 : remote.dtmfPayloadType;
            audioLine = audio + (dtmf >= 0 ? " " + dtmf : "");
            attributes.append("a=rtpmap:").append(audio).append(audio == G711.PCMA ? " PCMA/8000\r\n" : " PCMU/8000\r\n");
            if (dtmf >= 0) {
                attributes.append("a=rtpmap:").append(dtmf).append(" telephone-event/8000\r\n")
                        .append("a=fmtp:").append(dtmf).append(" 0-16\r\n");
            }
        }
        long session = System.currentTimeMillis() / 1000L;
        return "v=0\r\n"
                + "o=AirSIM " + session + " " + session + " IN IP4 " + localIp + "\r\n"
                + "s=AirSIM\r\n"
                + "c=IN IP4 " + localIp + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + active.rtpSocket.getLocalPort() + " RTP/AVP " + audioLine + "\r\n"
                + attributes
                + "a=ptime:20\r\n"
                + "a=sendrecv\r\n";
    }

    private static int chosenAudio(Sdp offer) {
        for (int payload : offer.payloads) if (payload == G711.PCMU || payload == G711.PCMA) return payload;
        return G711.PCMU;
    }

    /** One call. The gateway carries at most one call at a time, like the PCM bridge. */
    private static final class Leg {
        final boolean gsmInbound;
        final String sipCallId;
        final String localTag;
        final DatagramSocket rtpSocket;
        String telecomCallId = "";
        String localParty = "";
        String remoteParty = "";
        String remoteTag = "";
        String remoteTarget = "";
        String remoteSdp = "";
        String caller = "";
        SocketAddress peer;
        SipMessage invite;
        SipMessage sentInvite;
        SipMessage lastFinal;
        String inviteBranch = "";
        int inviteCseq;
        int localCseq = 1;
        boolean authTried;
        boolean confirmed;
        boolean ringingSent;
        boolean finalSent;
        boolean telecomActive;
        SipRtpSession media;
        ScheduledFuture<?> okRetransmit;

        Leg(boolean gsmInbound, String sipCallId, String localTag) throws IOException {
            this.gsmInbound = gsmInbound;
            this.sipCallId = sipCallId;
            this.localTag = localTag;
            this.rtpSocket = new DatagramSocket(new InetSocketAddress(0));
        }
    }

    /** The parts of a remote SDP the gateway needs. */
    private static final class Sdp {
        InetSocketAddress address;
        int[] payloads = new int[0];
        int dtmfPayloadType = -1;

        int firstAudio() {
            for (int payload : payloads) if (payload == G711.PCMU || payload == G711.PCMA) return payload;
            return G711.PCMU;
        }

        static Sdp parse(String body) {
            String host = "";
            int port = -1;
            Sdp sdp = new Sdp();
            for (String raw : body.split("\r?\n")) {
                String line = raw.trim();
                if (line.startsWith("c=IN IP4 ")) {
                    host = line.substring(9).trim();
                } else if (line.startsWith("m=audio ")) {
                    String[] parts = line.split("\\s+");
                    port = Integer.parseInt(parts[1]);
                    int[] payloads = new int[Math.max(0, parts.length - 3)];
                    for (int i = 3; i < parts.length; i++) payloads[i - 3] = Integer.parseInt(parts[i]);
                    sdp.payloads = payloads;
                } else if (line.toLowerCase(Locale.ROOT).startsWith("a=rtpmap:")
                        && line.toLowerCase(Locale.ROOT).contains("telephone-event/8000")) {
                    String value = line.substring(9, line.indexOf(' '));
                    sdp.dtmfPayloadType = Integer.parseInt(value.trim());
                }
            }
            if (host.isEmpty() || port <= 0) return null;
            sdp.address = new InetSocketAddress(host, port);
            return sdp;
        }
    }
}
