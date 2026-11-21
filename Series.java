package com.fnoema.chart;

import java.time.*;
import java.util.*;

final class Series {
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    record Bar(Instant time, double close) {}
    record Point(Instant time, double close, double ema) {}

    static List<Point> calculate(List<Bar> bars) {
        List<Point> points = new ArrayList<>();
        double ema = 0;
        for (int index = 0; index < bars.size(); index++) {
            Bar bar = bars.get(index);
            if (index < 9) {
                ema += bar.close() / 9;
            } else {
                ema += (bar.close() - ema) * 0.2;
            }
            points.add(new Point(bar.time(), bar.close(), index >= 8 ? ema : Double.NaN));
        }
        return points;
    }

    static Instant bucket(Instant trade, Instant now, int minutes) {
        ZonedDateTime local = trade.atZone(INDIA);
        ZonedDateTime open = local.toLocalDate().atTime(9, 15).atZone(INDIA);
        ZonedDateTime end = local.toLocalDate().atTime(15, 30).atZone(INDIA);
        if (local.getDayOfWeek().getValue() > 5 || local.isBefore(open) || !local.isBefore(end)
                || trade.isAfter(now.plusSeconds(5))) return null;
        long offset = Duration.between(open.toInstant(), trade).toMinutes();
        Instant start = open.plusMinutes(offset / minutes * minutes).toInstant();
        return now.isBefore(start.plusSeconds(minutes * 60L)) ? start : null;
    }
}
