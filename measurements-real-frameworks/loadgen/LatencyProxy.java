import java.io.*;
import java.net.*;
import java.util.concurrent.locks.LockSupport;

public class LatencyProxy {
    public static void main(String[] args) throws Exception {
        int listen = Integer.parseInt(args[0]), target = Integer.parseInt(args[1]);
        long delayNanos = Long.parseLong(args[2]) * 1000;
        String host = args.length > 3 ? args[3] : "localhost";
        try (ServerSocket server = new ServerSocket(listen)) {
            while (true) {
                Socket client = server.accept();
                Socket upstream = new Socket(host, target);
                client.setTcpNoDelay(true); upstream.setTcpNoDelay(true);
                Thread.ofPlatform().daemon().start(() -> pump(client, upstream, delayNanos));
                Thread.ofPlatform().daemon().start(() -> pump(upstream, client, delayNanos));
            }
        }
    }

    static void pump(Socket from, Socket to, long delayNanos) {
        byte[] buf = new byte[65536];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                long until = System.nanoTime() + delayNanos;
                while (System.nanoTime() < until) LockSupport.parkNanos(until - System.nanoTime());
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { from.close(); to.close(); } catch (IOException ignored) { }
        }
    }
}
