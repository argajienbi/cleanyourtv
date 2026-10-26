package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather card beside the world clock. Open-Meteo is free and needs no key, so there is nothing to configure
 * beyond the coordinates in config.xml or a theme. The last answer is cached on the TV: Home shows it at once and
 * keeps showing it when the network is down. Fetches run only while Home asks for them, on one background thread.
 */
final class WeatherWidget {
    private static final String TAG = "TvHome.Weather";
    private static final String PREFS = "weather_cache";
    private static final ExecutorService FETCHER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean fetching;

    interface Callback {
        void onWeather(Weather weather);
    }

    static final class Weather {
        final int temperature;
        final int high;
        final int low;
        final int code;

        Weather(int temperature, int high, int low, int code) {
            this.temperature = temperature;
            this.high = high;
            this.low = low;
            this.code = code;
        }
    }

    private WeatherWidget() {}

    /** A cached answer right away when it is fresh, otherwise the stale one while a new request runs. */
    static void load(Context context, String latitude, String longitude, int refreshMinutes, Callback callback) {
        final double lat, lon;
        try {
            lat = Double.parseDouble(latitude.trim());
            lon = Double.parseDouble(longitude.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
        Context app = context.getApplicationContext();
        SharedPreferences cache = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Weather cached = read(cache, lat, lon);
        if (cached != null && fresh(cache, refreshMinutes)) {
            MAIN.post(() -> callback.onWeather(cached));
            return;
        }
        if (cached != null) MAIN.post(() -> callback.onWeather(cached));
        synchronized (WeatherWidget.class) {
            if (fetching) return;
            fetching = true;
        }
        FETCHER.execute(() -> {
            Weather weather = null;
            try {
                weather = fetch(lat, lon);
            } catch (Exception e) {
                Log.w(TAG, "weather fetch failed: " + e.getMessage());
            }
            if (weather != null) write(cache, lat, lon, weather);
            synchronized (WeatherWidget.class) {
                fetching = false;
            }
            final Weather result = weather;
            if (result != null) MAIN.post(() -> callback.onWeather(result));
        });
    }

    /** The weather code as the words the card shows (WMO 4677 groups, Open-Meteo's codes). */
    static String describe(Context context, int code) {
        int id;
        if (code == 0) id = R.string.weather_clear;
        else if (code == 1 || code == 2) id = R.string.weather_partly;
        else if (code == 3) id = R.string.weather_cloudy;
        else if (code == 45 || code == 48) id = R.string.weather_fog;
        else if (code >= 51 && code <= 57) id = R.string.weather_drizzle;
        else if (code >= 61 && code <= 67) id = R.string.weather_rain;
        else if (code >= 71 && code <= 77) id = R.string.weather_snow;
        else if (code >= 80 && code <= 82) id = R.string.weather_showers;
        else if (code == 85 || code == 86) id = R.string.weather_snow_showers;
        else if (code >= 95) id = R.string.weather_storm;
        else id = R.string.weather_unknown;
        return context.getString(id);
    }

    private static Weather fetch(double latitude, double longitude) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1", latitude, longitude);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "CleanYourTV/3.0");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int n;
                while ((n = reader.read(buffer)) > 0 && text.length() < 64 * 1024) {
                    text.append(buffer, 0, n);
                }
            }
            JSONObject root = new JSONObject(text.toString());
            JSONObject current = root.getJSONObject("current");
            JSONObject daily = root.getJSONObject("daily");
            JSONArray highs = daily.getJSONArray("temperature_2m_max");
            JSONArray lows = daily.getJSONArray("temperature_2m_min");
            int high = highs.length() > 0 ? Math.round((float) highs.getDouble(0)) : Integer.MIN_VALUE;
            int low = lows.length() > 0 ? Math.round((float) lows.getDouble(0)) : Integer.MIN_VALUE;
            return new Weather(Math.round((float) current.getDouble("temperature_2m")), high, low,
                    current.optInt("weather_code", -1));
        } finally {
            connection.disconnect();
        }
    }

    private static Weather read(SharedPreferences cache, double latitude, double longitude) {
        if (Math.abs(cache.getFloat("latitude", Float.NaN) - latitude) > 0.0001
                || Math.abs(cache.getFloat("longitude", Float.NaN) - longitude) > 0.0001) return null;
        if (!cache.contains("temperature")) return null;
        return new Weather(cache.getInt("temperature", 0), cache.getInt("high", Integer.MIN_VALUE),
                cache.getInt("low", Integer.MIN_VALUE), cache.getInt("code", -1));
    }

    private static boolean fresh(SharedPreferences cache, int refreshMinutes) {
        long fetched = cache.getLong("fetched", 0);
        long age = System.currentTimeMillis() - fetched;
        return fetched > 0 && age >= 0 && age < Math.max(5, refreshMinutes) * 60_000L;
    }

    private static void write(SharedPreferences cache, double latitude, double longitude, Weather weather) {
        cache.edit().putFloat("latitude", (float) latitude)
                .putFloat("longitude", (float) longitude)
                .putInt("temperature", weather.temperature)
                .putInt("high", weather.high)
                .putInt("low", weather.low)
                .putInt("code", weather.code)
                .putLong("fetched", System.currentTimeMillis())
                .apply();
    }
}
