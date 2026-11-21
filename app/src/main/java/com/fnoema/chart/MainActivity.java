package com.fnoema.chart;

import android.app.*;
import android.os.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff0c1423;
    private static final int SURFACE = 0xff131e30;
    private static final int TEXT = 0xfff0f4fc;
    private static final int MUTED = 0xffa4b3c9;
    private static final long POLL_MS = 10000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss 'IST'", Locale.ENGLISH)
            .withZone(Series.INDIA);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> catalog = new TreeMap<>();
    private final Runnable poll = this::refresh;
    private SmartApi api;
    private ChartView chart;
    private TextView status, symbolLabel, quoteValue, emaValue;
    private Button connection;
    private String symbol = "RELIANCE";
    private int minutes = 5;
    private int generation;
    private boolean foreground;
    private boolean signingIn;
    private List<Series.Bar> history = Collections.emptyList();
    private long historyAt;
    private SmartApi.Quote lastQuote;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        loadCatalog();

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BACKGROUND);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(22), dp(20), dp(24));
        page.addView(content);
        setContentView(page);

        add(content, label("F&O EMA", 30, TEXT, true), 0);
        add(content, label("NSE cash market  /  EMA 9", 14, MUTED, false), 4);
        LinearLayout filters = row();
        symbolLabel = label(symbol, 16, TEXT, true);
        Button stock = button("Choose stock");
        stock.setOnClickListener(view -> chooseStock());
        filters.addView(symbolLabel, new LinearLayout.LayoutParams(0, -2, 1));
        filters.addView(stock);
        add(content, filters, 20);

        RadioGroup intervals = new RadioGroup(this);
        intervals.setOrientation(LinearLayout.HORIZONTAL);
        for (int interval : new int[] {1, 5, 15}) {
            RadioButton option = new RadioButton(this);
            option.setId(interval);
            option.setText(interval + "m");
            option.setTextColor(TEXT);
            option.setButtonTintList(ColorStateList.valueOf(ChartView.EMA));
            intervals.addView(option, new RadioGroup.LayoutParams(0, dp(46), 1));
        }
        intervals.check(minutes);
        intervals.setOnCheckedChangeListener((group, checked) -> {
            minutes = checked;
            resetSeries();
        });
        add(content, intervals, 8);

        LinearLayout metrics = row();
        quoteValue = metric(metrics, "LAST PRICE", ChartView.CLOSE);
        emaValue = metric(metrics, "EMA (9)", ChartView.EMA);
        add(content, metrics, 12);

        chart = new ChartView(this);
        chart.setBackground(card(SURFACE));
        chart.setClipToOutline(true);
        LinearLayout.LayoutParams chartSize = new LinearLayout.LayoutParams(-1, dp(290));
        chartSize.topMargin = dp(14);
        content.addView(chart, chartSize);
        LinearLayout legend = row();
        TextView closeLegend = label("● Close price", 13, ChartView.CLOSE, false);
        closeLegend.setPadding(0, 0, dp(20), 0);
        legend.addView(closeLegend);
        legend.addView(label("● EMA (9)", 13, ChartView.EMA, false));
        add(content, legend, 10);

        status = label("", 13, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, status, 18);
        connection = button("Connect Angel One");
        connection.setOnClickListener(view -> {
            if (api == null) showLogin();
            else {
                api = null;
                connection.setText("Connect Angel One");
                resetSeries();
            }
        });
        add(content, connection, 10);
        showDemo();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (api != null) refresh();
    }

    @Override protected void onStop() {
        foreground = false;
        generation++;
        handler.removeCallbacks(poll);
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadCatalog() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("stocks.csv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = line.split(",", 2);
                catalog.put(row[0], row[1]);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Missing stock catalog", error);
        }
    }

    private void chooseStock() {
        LinearLayout body = column();
        body.setPadding(dp(18), 0, dp(18), dp(12));
        EditText query = new EditText(this);
        query.setSingleLine();
        query.setHint("Search symbol");
        body.addView(query);
        ListView list = new ListView(this);
        body.addView(list, new LinearLayout.LayoutParams(-1, dp(300)));
        ArrayAdapter<String> stocks = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>(catalog.keySet()));
        list.setAdapter(stocks);
        query.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                stocks.getFilter().filter(text);
            }
            public void afterTextChanged(android.text.Editable text) {}
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Choose an F&O stock")
                .setView(body).setNegativeButton("Cancel", null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            symbol = stocks.getItem(position);
            symbolLabel.setText(symbol);
            dialog.dismiss();
            resetSeries();
        });
        dialog.show();
    }

    private void showLogin() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = column();
        form.setPadding(dp(22), dp(8), dp(22), dp(14));
        scroll.addView(form);
        EditText key = field(form, "SmartAPI key", true, false);
        EditText client = field(form, "Angel One client code", false, false);
        EditText pin = field(form, "Angel One PIN", true, true);
        EditText otp = field(form, "Current six-digit TOTP", true, true);
        EditText ip = field(form, "Public IP (SmartAPI header)", false, false);
        add(form, label("Enter your current internet-facing IP. Use the IP registered with Angel One if your API key requires one.", 13, MUTED, false), 9);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connect Angel One").setView(scroll)
                .setNegativeButton("Cancel", null).setPositiveButton("Connect", null).create();
        dialog.setOnDismissListener(ignored -> {
            key.getText().clear();
            client.getText().clear();
            pin.getText().clear();
            otp.getText().clear();
            ip.getText().clear();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String k = value(key), c = value(client), p = value(pin), o = value(otp), address = value(ip);
            if (k.isEmpty() || c.isEmpty() || p.isEmpty() || !o.matches("[0-9]{6}")
                    || !address.matches("[0-9a-fA-F:.]{3,45}")) {
                Toast.makeText(this, "Enter all fields, a six-digit TOTP, and a valid IP address.", Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            signingIn = true;
            connection.setEnabled(false);
            status.setText("Connecting to Angel One…");
            SmartApi session = new SmartApi(k, address);
            worker.execute(() -> {
                String failure = null;
                try {
                    session.login(c, p, o);
                } catch (Exception error) {
                    failure = message(error);
                }
                String result = failure;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    signingIn = false;
                    connection.setEnabled(true);
                    if (result != null) {
                        status.setText("DEMO · " + result);
                        return;
                    }
                    api = session;
                    connection.setText("Disconnect Angel One");
                    resetSeries();
                });
            });
        });
    }

    private void resetSeries() {
        generation++;
        handler.removeCallbacks(poll);
        history = Collections.emptyList();
        historyAt = 0;
        lastQuote = null;
        if (api == null) {
            showDemo();
        } else {
            updateChart(history, null);
            status.setText("Loading Angel One data…");
            if (foreground) refresh();
        }
    }

    private void refresh() {
        if (!foreground || api == null) return;
        int request = ++generation;
        int interval = minutes;
        String token = catalog.get(symbol);
        SmartApi session = api;
        boolean loadHistory = history.isEmpty() || SystemClock.elapsedRealtime() - historyAt >= 30000;
        worker.execute(() -> {
            List<Series.Bar> newHistory = null;
            SmartApi.Quote recent = null;
            String issue = null;
            boolean expired = false;
            if (loadHistory) {
                try {
                    newHistory = session.candles(token, interval);
                    if (newHistory.isEmpty()) issue = "No historical candles returned.";
                } catch (Exception error) {
                    issue = "Candles · " + message(error);
                    expired = error instanceof SmartApi.Failure && ((SmartApi.Failure) error).expired;
                }
            }
            if (!expired) {
                try {
                    recent = session.quote(token);
                } catch (Exception error) {
                    expired = error instanceof SmartApi.Failure && ((SmartApi.Failure) error).expired;
                    issue = issue == null || expired ? "Quote · " + message(error) : issue + " " + message(error);
                }
            }
            List<Series.Bar> candles = newHistory;
            SmartApi.Quote quote = recent;
            String result = issue;
            boolean reconnect = expired;
            runOnUiThread(() -> {
                if (request != generation || !foreground || isDestroyed()) return;
                if (candles != null && !candles.isEmpty()) {
                    history = candles;
                    historyAt = SystemClock.elapsedRealtime();
                }
                if (quote != null) lastQuote = quote;
                renderLive(result);
                if (reconnect) {
                    api = null;
                    connection.setText("Reconnect Angel One");
                } else {
                    handler.postDelayed(poll, POLL_MS);
                }
            });
        });
    }

    private void renderLive(String issue) {
        Instant now = Instant.now();
        Instant trade = lastQuote == null ? null : lastQuote.time();
        Instant bucket = trade == null ? null : Series.bucket(trade, now, minutes);
        List<Series.Bar> bars = new ArrayList<>(history);
        if (bucket != null && !bars.isEmpty()) {
            int last = bars.size() - 1;
            Series.Bar open = new Series.Bar(bucket, lastQuote.price());
            int order = bucket.compareTo(bars.get(last).time());
            if (order == 0) bars.set(last, open);
            else if (order > 0) bars.add(open);
        }
        updateChart(bars, lastQuote == null ? null : lastQuote.price());
        String stamp = trade == null ? "Trade time unavailable" : "Last trade " + CLOCK.format(trade);
        if (issue != null) status.setText(issue + "  " + stamp);
        else if (lastQuote == null) status.setText("Waiting for an Angel One quote…");
        else status.setText((bucket == null ? "Latest available quote" : "Open candle · refreshes every ~10s") + "\n" + stamp);
    }

    private void showDemo() {
        List<Series.Bar> bars = new ArrayList<>();
        Instant start = LocalDate.of(2026, 11, 19).atTime(9, 15).atZone(Series.INDIA).toInstant();
        Random random = new Random(symbol.hashCode() * 31L + minutes);
        double price = 400 + Math.floorMod(symbol.hashCode(), 1500);
        int count = Math.min(80, 375 / minutes);
        for (int index = 0; index < count; index++) {
            price += (random.nextDouble() - 0.38) * 4;
            bars.add(new Series.Bar(start.plusSeconds(index * minutes * 60L), price));
        }
        updateChart(bars, bars.get(bars.size() - 1).close());
        status.setText(signingIn ? "Connecting to Angel One…" : "DEMO · Simulated prices");
    }

    private void updateChart(List<Series.Bar> bars, Double quote) {
        List<Series.Point> points = Series.calculate(bars);
        chart.update(points);
        double ema = points.isEmpty() ? Double.NaN : points.get(points.size() - 1).ema();
        quoteValue.setText(quote == null ? "—" : price(quote));
        emaValue.setText(Double.isFinite(ema) ? price(ema) : "—");
    }

    private static String message(Exception error) {
        if (error instanceof SmartApi.Failure) return error.getMessage();
        if (error instanceof java.net.SocketTimeoutException) return "Angel One timed out.";
        if (error instanceof IOException) return "Unable to read Angel One data. Check your connection.";
        return "Angel One returned an unexpected response.";
    }

    private static String price(double amount) { return String.format(Locale.ENGLISH, "₹%,.2f", amount); }
    private static String value(EditText field) { return field.getText().toString().trim(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(String text) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setBackgroundTintList(ColorStateList.valueOf(0xff263b56));
        return view;
    }

    private GradientDrawable card(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    private TextView metric(LinearLayout parent, String name, int color) {
        LinearLayout block = column();
        block.setBackground(card(SURFACE));
        block.setPadding(dp(14), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) parameters.leftMargin = dp(8);
        parent.addView(block, parameters);
        block.addView(label(name, 11, MUTED, true));
        TextView value = label("—", 22, color, true);
        add(block, value, 4);
        return value;
    }

    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, -2);
        parameters.topMargin = dp(margin);
        parent.addView(child, parameters);
    }

    private EditText field(LinearLayout parent, String name, boolean secret, boolean numeric) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint(name);
        input.setContentDescription(name);
        int type = numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT;
        if (secret) type |= numeric ? InputType.TYPE_NUMBER_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD;
        input.setInputType(type);
        input.setSaveEnabled(false);
        add(parent, input, 4);
        return input;
    }
}
