package com.airsim.phonecontrol;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges one call between RTP (G.711, 20 ms) and the Shizuku PCM bridge on 127.0.0.1:7580.
 * Downlink: phone-call PCM → RTP to the SIP server. Uplink: RTP from the server → phone-call PCM.
 * RFC 4733 telephone-events from the SIP side are reported through {@link DtmfListener}.
 */
final class SipRtpSession implements Closeable {
    interface DtmfListener { void onDtmf(char digit); }

    private static final int FRAME_BYTES = 320;
    private static final int FRAME_SAMPLES = 160;
    private static final String DTMF_EVENTS = "0123456789*#ABCD";

    private final DatagramSocket socket;
    private final int audioPayloadType;
    private final int dtmfPayloadType;
    private final DtmfListener dtmf;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong sentPackets = new AtomicLong();
    private final AtomicLong receivedPackets = new AtomicLong();
    private volatile SocketAddress remote;
    private volatile Socket bridge;

    SipRtpSession(DatagramSocket socket, InetSocketAddress remote, int audioPayloadType, int dtmfPayloadType,
            DtmfListener dtmf) {
        this.socket = socket;
        this.remote = remote;
        this.audioPayloadType = audioPayloadType;
        this.dtmfPayloadType = dtmfPayloadType;
        this.dtmf = dtmf;
    }

    void start() throws IOException {
        Socket connection = new Socket();
        connection.connect(new InetSocketAddress("127.0.0.1", 7580), 2_000);
        connection.setTcpNoDelay(true);
        connection.getOutputStream().write("AIRSIMPCM1\n".getBytes(StandardCharsets.US_ASCII));
        connection.setSoTimeout(3_000);
        if (!VoWLANPCMProtocol.acceptReady(connection.getInputStream())) {
            connection.close();
            throw new IOException("shell PCM handshake failed");
        }
        connection.setSoTimeout(0);
        bridge = connection;
        Thread downlink = new Thread(this::downlinkLoop, "airsim-sip-downlink");
        Thread uplink = new Thread(this::uplinkLoop, "airsim-sip-uplink");
        downlink.start();
        uplink.start();
        BridgeLog.info("sip_media_started audio_pt=" + audioPayloadType + " dtmf_pt=" + dtmfPayloadType
                + " local_rtp_port=" + socket.getLocalPort());
    }

    private void downlinkLoop() {
        byte[] pcm = new byte[FRAME_BYTES];
        byte[] packet = new byte[12 + FRAME_SAMPLES];
        SecureRandom random = new SecureRandom();
        int ssrc = random.nextInt();
        int sequence = random.nextInt(0x10000);
        long timestamp = random.nextInt() & 0xffffffffL;
        boolean first = true;
        try {
            DataInputStream input = new DataInputStream(bridge.getInputStream());
            while (running.get()) {
                input.readFully(pcm);
                packet[0] = (byte) 0x80;
                packet[1] = (byte) ((first ? 0x80 : 0) | (audioPayloadType & 0x7f));
                packet[2] = (byte) (sequence >> 8);
                packet[3] = (byte) sequence;
                packet[4] = (byte) (timestamp >> 24);
                packet[5] = (byte) (timestamp >> 16);
                packet[6] = (byte) (timestamp >> 8);
                packet[7] = (byte) timestamp;
                packet[8] = (byte) (ssrc >> 24);
                packet[9] = (byte) (ssrc >> 16);
                packet[10] = (byte) (ssrc >> 8);
                packet[11] = (byte) ssrc;
                byte[] payload = new byte[FRAME_SAMPLES];
                G711.encode(audioPayloadType, pcm, FRAME_SAMPLES, payload);
                System.arraycopy(payload, 0, packet, 12, FRAME_SAMPLES);
                socket.send(new DatagramPacket(packet, packet.length, remote));
                sentPackets.incrementAndGet();
                sequence = (sequence + 1) & 0xffff;
                timestamp = (timestamp + FRAME_SAMPLES) & 0xffffffffL;
                first = false;
            }
        } catch (IOException error) {
            if (running.get()) BridgeLog.error("sip_media_downlink_failed", error);
        } finally {
            close();
        }
    }

    private void uplinkLoop() {
        byte[] buffer = new byte[1500];
        byte[] pcm = new byte[FRAME_BYTES * 4];
        byte[] pending = new byte[FRAME_BYTES];
        int pendingBytes = 0;
        long lastDtmfTimestamp = -1;
        boolean latched = false;
        try {
            socket.setSoTimeout(1_000);
            OutputStream output = bridge.getOutputStream();
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                int length = packet.getLength();
                if (length < 12 || (buffer[0] & 0xC0) != 0x80) continue;
                int csrcCount = buffer[0] & 0x0F;
                int offset = 12 + 4 * csrcCount;
                if ((buffer[0] & 0x10) != 0 && length >= offset + 4) {
                    offset += 4 + 4 * (((buffer[offset + 2] & 0xff) << 8) | (buffer[offset + 3] & 0xff));
                }
                if ((buffer[0] & 0x20) != 0) length -= buffer[length - 1] & 0xff;
                int payloadLength = length - offset;
                if (payloadLength <= 0) continue;
                int payloadType = buffer[1] & 0x7f;
                receivedPackets.incrementAndGet();
                if (!latched) {
                    // Symmetric RTP: answer to wherever the server actually sends from.
                    remote = packet.getSocketAddress();
                    latched = true;
                }
                if (payloadType == dtmfPayloadType) {
                    long eventTimestamp = ((buffer[4] & 0xffL) << 24) | ((buffer[5] & 0xffL) << 16)
                            | ((buffer[6] & 0xffL) << 8) | (buffer[7] & 0xffL);
                    int event = buffer[offset] & 0xff;
                    if (eventTimestamp != lastDtmfTimestamp && event < DTMF_EVENTS.length()) {
                        lastDtmfTimestamp = eventTimestamp;
                        dtmf.onDtmf(DTMF_EVENTS.charAt(event));
                    }
                    continue;
                }
                if (payloadType != audioPayloadType) continue;
                int samples = Math.min(payloadLength, pcm.length / 2);
                G711.decode(audioPayloadType, buffer, offset, samples, pcm);
                // Re-chunk into the bridge's fixed 320-byte frames.
                int consumed = 0;
                int available = samples * 2;
                while (consumed < available) {
                    int take = Math.min(FRAME_BYTES - pendingBytes, available - consumed);
                    System.arraycopy(pcm, consumed, pending, pendingBytes, take);
                    pendingBytes += take;
                    consumed += take;
                    if (pendingBytes == FRAME_BYTES) {
                        output.write(pending);
                        pendingBytes = 0;
                    }
                }
            }
        } catch (IOException error) {
            if (running.get()) BridgeLog.error("sip_media_uplink_failed", error);
        } finally {
            close();
        }
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        Socket active = bridge;
        if (active != null) {
            try { active.close(); } catch (IOException ignored) {}
        }
        socket.close();
        BridgeLog.info("sip_media_closed rtp_sent=" + sentPackets.get() + " rtp_received=" + receivedPackets.get());
    }
}
