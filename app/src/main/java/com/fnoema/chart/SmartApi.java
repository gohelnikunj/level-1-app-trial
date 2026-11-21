package com.fnoema.chart;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

final class SmartApi {
    private static final String BASE = "https://apiconnect.angelone.in";
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TRADE_TIME = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
    private final String apiKey;
    private final String ip;
    private String jwt;

    record Quote(Instant time, double price) {}
    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean expired;
        Failure(String message, boolean expired) { super(message); this.expired = expired; }
    }

    SmartApi(String apiKey, String ip) {
        this.apiKey = apiKey;
        this.ip = ip;
    }

    void login(String client, String pin, String totp) throws Exception {
        JSONObject body = new JSONObject().put("clientcode", client).put("password", pin).put("totp", totp);
        jwt = request("/rest/auth/angelbroking/user/v1/loginByPassword", body)
                .getJSONObject("data").getString("jwtToken");
        if (jwt.trim().isEmpty()) throw new IOException("Angel One returned an empty session token.");
    }

    List<Series.Bar> candles(String token, int minutes) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(Series.INDIA);
        String interval = switch (minutes) {
            case 1 -> "ONE_MINUTE";
            case 5 -> "FIVE_MINUTE";
            case 15 -> "FIFTEEN_MINUTE";
            default -> throw new IllegalArgumentException("Unsupported candle interval");
        };
        JSONObject body = new JSONObject().put("exchange", "NSE").put("symboltoken", token)
                .put("interval", interval).put("fromdate", now.minusDays(7).format(REQUEST_TIME))
                .put("todate", now.format(REQUEST_TIME));
        JSONArray data = request("/rest/secure/angelbroking/historical/v1/getCandleData", body)
                .optJSONArray("data");
        Map<Instant, Series.Bar> ordered = new TreeMap<>();
        if (data != null) {
            for (int index = 0; index < data.length(); index++) {
                JSONArray row = data.getJSONArray(index);
                Instant time = OffsetDateTime.parse(row.getString(0)).toInstant();
                ordered.put(time, new Series.Bar(time, row.getDouble(4)));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    Quote quote(String token) throws Exception {
        JSONObject body = new JSONObject().put("mode", "FULL").put("exchangeTokens",
                new JSONObject().put("NSE", new JSONArray().put(token)));
        JSONArray fetched = request("/rest/secure/angelbroking/market/v1/quote", body)
                .getJSONObject("data").getJSONArray("fetched");
        if (fetched.length() == 0) throw new IOException("Angel One returned no quote for this symbol.");
        JSONObject quote = fetched.getJSONObject(0);
        double price = quote.optDouble("ltp");
        if (!Double.isFinite(price) || price <= 0) throw new IOException("Angel One returned an invalid quote.");
        try {
            Instant time = LocalDateTime.parse(quote.optString("exchTradeTime").trim(), TRADE_TIME)
                    .atZone(Series.INDIA).toInstant();
            return new Quote(time, price);
        } catch (DateTimeException ignored) {
            return new Quote(null, price);
        }
    }

    private JSONObject request(String path, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(BASE + path).openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-PrivateKey", apiKey);
            connection.setRequestProperty("X-UserType", "USER");
            connection.setRequestProperty("X-SourceID", "WEB");
            connection.setRequestProperty("X-ClientLocalIP", "127.0.0.1");
            connection.setRequestProperty("X-ClientPublicIP", ip);
            connection.setRequestProperty("X-MACAddress", "00:00:00:00:00:00");
            if (jwt != null) connection.setRequestProperty("Authorization", "Bearer " + jwt);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            JSONObject response = new JSONObject();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    StringBuilder raw = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) raw.append(line);
                    try {
                        response = new JSONObject(raw.toString());
                    } catch (JSONException ignored) {
                        // HTTP status still describes a non-JSON gateway response.
                    }
                }
            }
            if (status >= 200 && status < 300 && response.optBoolean("status")) return response;
            String code = response.optString("errorcode");
            boolean expired = jwt != null && (status == 401 || code.equals("AG8001") || code.equals("AG8002") || code.equals("AG8003"));
            String suffix = code.matches("[A-Z0-9]{1,16}") ? " (" + code + ")" : "";
            throw new Failure(expired ? "Angel One session expired. Reconnect to resume."
                    : "Angel One request failed — HTTP " + status + suffix + ".", expired);
        } finally {
            connection.disconnect();
        }
    }
}
