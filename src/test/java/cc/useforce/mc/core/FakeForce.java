package cc.useforce.mc.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A real `force api` for the tests, started as a subprocess: plain JDK only. */
public final class FakeForce {
    private static final Pattern ID = Pattern.compile("\"id\":\\s*(\\d+)");
    private static final Pattern OP = Pattern.compile("\"op\":\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("missing")) System.exit(127);
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = r.readLine()) != null) {
            Matcher m = ID.matcher(line);
            String id = m.find() ? m.group(1) : "";
            String op = op(line);
            String ok = op.equals("hello")
                    ? "{\"protocol\":1,\"force\":\"9.9.9-é✅\",\"features\":[]}" : "{}";
            w.println("{\"id\":" + id + ",\"ok\":" + ok + "}");
        }
    }

    private static String op(String line) {
        Matcher m = OP.matcher(line);
        return m.find() ? m.group(1) : "";
    }
}
