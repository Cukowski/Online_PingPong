import java.net.Socket;
import java.net.InetSocketAddress;

/**
 * Simple program to test TCP connectivity to a given host and port.
 * Usage: java TestConnection <host> <port>
 */
public class TestConnection {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java TestConnection <host> <port>");
            System.exit(1);
        }
        String host = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("Port must be an integer.");
            return;
        }

        System.out.println("Attempting to connect to " + host + ":" + port + "...");
        try (Socket socket = new Socket()) {
            // 5 s timeout
            socket.connect(new InetSocketAddress(host, port), 5000);
            System.out.println("Connection successful!");
        } catch (Exception e) {
            System.out.println("Connection failed: " + e.getMessage());
        }
    }
}
