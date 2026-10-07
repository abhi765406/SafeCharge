package com.safecharge.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * A very small ADB client that talks to the phone's own adbd over 127.0.0.1.
 * It lets the app run "shell" commands (pm disable-user, am force-stop,
 * pm trim-caches ...) without root, once you have switched adbd to TCP mode
 * with a single "adb tcpip 5555" from a computer.
 *
 * No external libraries: only java.net / java.security.
 */
final class AdbClient implements Closeable {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;
    private static final int LOCAL_ID = 1;

    private static final String PREF_KEY = "adb_private_key";

    private final Context ctx;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;

    AdbClient(Context c) {
        this.ctx = c.getApplicationContext();
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    // ------------------------------------------------------------ connect

    void connect() throws Exception {
        int port = Prefs.get(ctx).getInt(Prefs.ADB_PORT, 5555);
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(45000); // leaves time to tap "Allow" on the phone the first time
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        KeyPair kp = loadOrCreateKey();

        send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes("UTF-8"));
        boolean signatureSent = false;
        boolean keySent = false;
        for (int i = 0; i < 6; i++) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                socket.setSoTimeout(120000);
                return;
            } else if (m.cmd == A_AUTH && m.arg0 == 1) {
                if (!signatureSent) {
                    send(A_AUTH, 2, 0, sign(kp.getPrivate(), m.data));
                    signatureSent = true;
                } else if (!keySent) {
                    send(A_AUTH, 3, 0, publicKeyBytes(kp));
                    keySent = true;
                } else {
                    throw new IOException("Phone rejected the connection (was Allow tapped?)");
                }
            } else if (m.cmd == A_STLS) {
                throw new IOException("This Android version needs TLS for ADB (not supported here)");
            } else {
                throw new IOException("Unexpected reply from adbd");
            }
        }
        throw new IOException("ADB handshake failed");
    }

    /** Runs one shell command and returns everything it printed. */
    String shell(String command) throws IOException {
        send(A_OPEN, LOCAL_ID, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY) {
                continue;
            } else if (m.cmd == A_WRTE) {
                buf.write(m.data, 0, m.data.length);
                send(A_OKAY, LOCAL_ID, m.arg0, null);
            } else if (m.cmd == A_CLSE) {
                break;
            }
        }
        return buf.toString("UTF-8");
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------ wire format

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += (data[i] & 0xff);
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // checksum
        int magic = h.getInt();
        if (magic != (m.cmd ^ 0xffffffff)) throw new IOException("Bad ADB message");
        if (len < 0 || len > 1024 * 1024) throw new IOException("Bad ADB length");
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------ keys

    private KeyPair loadOrCreateKey() throws Exception {
        SharedPreferences p = Prefs.get(ctx);
        String stored = p.getString(PREF_KEY, null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (stored != null) {
            try {
                PrivateKey priv = kf.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.decode(stored, Base64.NO_WRAP)));
                RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
                return new KeyPair(kf.generatePublic(
                        new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent())), priv);
            } catch (Exception e) {
                // fall through and make a fresh key
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        p.edit().putString(PREF_KEY,
                Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP)).apply();
        return kp;
    }

    /** adbd gives a 20 byte token; we sign it as if it were an already-computed SHA-1. */
    static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        byte[] sha1Prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
                0x05, 0x00, 0x04, 0x14};
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        s.update(sha1Prefix);
        s.update(token);
        return s.sign();
    }

    /** Public key in the odd little-endian layout Android's adbd expects, base64 + name. */
    static byte[] publicKeyBytes(KeyPair kp) throws Exception {
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kp.getPrivate();
        BigInteger n = priv.getModulus();
        BigInteger e = priv.getPublicExponent();
        final int words = 64; // 2048 bits / 32
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = r32.subtract(n.mod(r32).modInverse(r32)).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv);
        for (int i = 0; i < words; i++) bb.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < words; i++) bb.putInt(rr.shiftRight(32 * i).intValue());
        bb.putInt(e.intValue());

        String text = Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " safecharge@phone\0";
        return text.getBytes("UTF-8");
    }
}
