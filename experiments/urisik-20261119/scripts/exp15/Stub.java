// 식품안전나라 외부 API 대역(자바). 도커 네트워크 안에서 실행해 호스트(host-gateway) 경로를 거치지 않게 한다.
// 사용: java Stub.java 80 20   (포트, 지연 ms). 어떤 GET이든 200과 빈 결과를 고정 지연 후 돌려준다.
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class Stub {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long delay = Long.parseLong(args[1]);
        byte[] body = "{\"COOKRCP01\":{\"total_count\":\"0\",\"row\":[],\"RESULT\":{\"MSG\":\"해당하는 데이터가 없습니다.\",\"CODE\":\"INFO-200\"}}}"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 1024);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) { }
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("stub up on " + port + " delay " + delay + "ms");
    }
}
