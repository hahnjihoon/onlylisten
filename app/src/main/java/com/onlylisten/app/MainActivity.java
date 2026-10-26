package com.onlylisten.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AdbStream;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
    private AudioManager audio;
    private AudioDeviceCallback deviceCallback;
    private SharedPreferences preferences;
    private WirelessAdbManager adb;
    private ImageButton powerButton;
    private Button setupButton;
    private Button diagnosticsButton;
    private TextView deviceStatus;
    private TextView powerState;
    private TextView statusDetail;
    private Runnable refresh;
    private Runnable runCommand;
    private Runnable poll;
    private String requestedAction;
    private String statusMessage;
    private boolean connected;
    private boolean busy;
    private boolean resumed;
    private boolean closing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
        preferences = getSharedPreferences("bluetooth_policy", MODE_PRIVATE);
        getSystemService(NotificationManager.class).cancel(41);
        getSystemService(NotificationManager.class).deleteNotificationChannel("wireless-pairing");
        audio = getSystemService(AudioManager.class);
        powerButton = findViewById(R.id.power_button);
        setupButton = findViewById(R.id.setup_button);
        diagnosticsButton = findViewById(R.id.diagnostics_button);
        deviceStatus = findViewById(R.id.device_status);
        powerState = findViewById(R.id.power_state);
        statusDetail = findViewById(R.id.status_detail);
        statusMessage = preferences.getString("last_result", "전원 버튼을 눌러 휴대폰 마이크 모드를 켜 주세요.");

        refresh = () -> {
            if (closing || isDestroyed()) {
                return;
            }
            String pairingResult = preferences.getString("adb_pairing_result", null);
            if (pairingResult != null) {
                statusMessage = pairingResult;
                preferences.edit().remove("adb_pairing_result").apply();
            }
            if (!busy && preferences.getBoolean("adb_pairing_completed", false)) {
                preferences.edit().remove("adb_pairing_completed").apply();
                main.post(() -> {
                    requestedAction = "status";
                    runCommand.run();
                });
            }
            boolean granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            connected = false;
            AudioDeviceInfo earphone = null;
            if (granted && audio != null) {
                try {
                    AudioDeviceInfo current = audio.getCommunicationDevice();
                    if (current != null && (current.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || current.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                        earphone = current;
                    }
                    if (earphone == null) {
                        for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                    || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                                earphone = device;
                                break;
                            }
                        }
                    }
                    if (earphone == null) {
                        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                    || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                    || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                                earphone = device;
                                break;
                            }
                        }
                    }
                    connected = earphone != null;
                } catch (SecurityException ignored) {
                    granted = false;
                }
            }
            boolean enabled = preferences.getBoolean("phone_mic_enabled", false);
            boolean recovery = preferences.contains("routing_backup") && !enabled;
            powerButton.setEnabled(connected && !busy);
            powerButton.setSelected(enabled);
            powerButton.setActivated(recovery);
            powerButton.setAlpha(!connected ? 0.4f : busy ? 0.65f : 1f);
            powerButton.setContentDescription(enabled || recovery
                    ? "휴대폰 마이크 모드 끄기" : "휴대폰 마이크 모드 켜기");
            powerState.setText(busy ? "연결 중" : recovery ? "복원 필요" : enabled ? "ON" : "OFF");
            powerState.setTextColor(recovery ? Color.rgb(231, 186, 105)
                    : enabled ? getColor(R.color.accent) : getColor(R.color.muted));
            setupButton.setEnabled(!busy);
            diagnosticsButton.setEnabled(!busy);
            setupButton.setText(preferences.getBoolean("adb_paired", false)
                    ? "연결 설정 · 다시 연결" : "최초 연결 설정");
            if (!granted) {
                deviceStatus.setText("주변 기기 권한 필요");
                statusDetail.setText("최초 연결 설정을 눌러 주변 기기 권한을 허용해 주세요.");
            } else if (!connected) {
                deviceStatus.setText("블루투스 이어폰 연결 안 됨");
                statusDetail.setText(enabled || recovery
                        ? "이어폰을 다시 연결한 뒤 OFF를 눌러 원래 설정을 복원해 주세요."
                        : "이어폰이 연결됐을 때만 ON/OFF 버튼을 사용할 수 있습니다.");
            } else {
                deviceStatus.setText("연결됨 · " + earphone.getProductName());
                statusDetail.setText(statusMessage);
            }
        };

        runCommand = () -> {
            if (busy || closing) {
                return;
            }
            String action = requestedAction;
            if (!action.equals("status") && !connected) {
                refresh.run();
                return;
            }
            if (!preferences.getBoolean("adb_paired", false)) {
                setupButton.performClick();
                return;
            }
            busy = true;
            statusMessage = action.equals("status") ? "시스템 연결과 통화 경로를 확인하고 있습니다."
                    : action.equals("on") ? "이어폰 출력과 휴대폰 마이크를 지정하고 있습니다."
                    : "원래 통화 설정으로 복원하고 있습니다.";
            refresh.run();
            worker.execute(() -> {
                JSONObject reply = null;
                String failure = null;
                WifiManager.MulticastLock multicast = null;
                try {
                    if (adb == null) {
                        adb = new WirelessAdbManager(getApplicationContext());
                    }
                    if (!adb.isConnected()) {
                        WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
                        if (wifi != null) {
                            multicast = wifi.createMulticastLock("onlylisten-discovery");
                            multicast.setReferenceCounted(false);
                            multicast.acquire();
                        }
                        adb.connectTls(getApplicationContext(), 7000);
                    }
                    if (!adb.isConnected()) {
                        throw new IllegalStateException("무선 디버깅 연결에 실패했습니다. 연결 설정에서 디버깅을 켜고 다시 연결해 주세요.");
                    }
                    String backup = preferences.getString("routing_backup", "");
                    String[] stages = action.equals("on") && backup.isEmpty()
                            ? new String[]{"snapshot", "on"} : new String[]{action};
                    for (String stage : stages) {
                        String apk = "'" + getApplicationInfo().sourceDir.replace("'", "'\\''") + "'";
                        String command = "CLASSPATH=" + apk
                                + " app_process /system/bin com.onlylisten.app.AudioRouteCommand " + stage;
                        if (stage.equals("on") || stage.equals("off")) {
                            if (backup.isEmpty()) {
                                throw new IllegalStateException("복원할 원래 설정이 없습니다.");
                            }
                            command += " " + Base64.encodeToString(backup.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                        }
                        AdbStream stream = adb.openStream("shell:" + command);
                        ScheduledFuture<?> timeout = timeouts.schedule(() -> {
                            try {
                                stream.close();
                            } catch (Exception ignored) {
                            }
                        }, 20, TimeUnit.SECONDS);
                        String output;
                        try (stream; InputStream input = stream.openInputStream();
                             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                            byte[] buffer = new byte[4096];
                            int count;
                            while (true) {
                                try {
                                    count = input.read(buffer);
                                } catch (java.io.IOException closed) {
                                    if (stream.isClosed()) {
                                        break;
                                    }
                                    throw closed;
                                }
                                if (count == -1) {
                                    break;
                                }
                                bytes.write(buffer, 0, count);
                                if (bytes.size() > 131072) {
                                    throw new IllegalStateException("시스템 응답이 너무 큽니다.");
                                }
                            }
                            output = bytes.toString(StandardCharsets.UTF_8.name());
                        } finally {
                            timeout.cancel(false);
                        }
                        int marker = output.lastIndexOf("ONLYLISTEN_RESULT:");
                        if (marker < 0) {
                            String detail = output.trim();
                            if (detail.length() > 1000) {
                                detail = detail.substring(detail.length() - 1000);
                            }
                            throw new IllegalStateException("오디오 제어 코드가 응답하지 않았습니다. " + detail);
                        }
                        reply = new JSONObject(output.substring(marker + "ONLYLISTEN_RESULT:".length())
                                .split("\\r?\\n", 2)[0]);
                        preferences.edit().putString("last_diagnostics", reply.toString()).commit();
                        if (!reply.optBoolean("ok")) {
                            if (stage.equals("on") && (reply.optBoolean("rolledBack")
                                    || !reply.optBoolean("changed", true))) {
                                preferences.edit().remove("routing_backup").putBoolean("phone_mic_enabled", false).commit();
                            }
                            throw new IllegalStateException(reply.optString("step") + ": " + reply.optString("error"));
                        }
                        if (stage.equals("snapshot")) {
                            backup = reply.getJSONObject("snapshot").toString();
                            if (!preferences.edit().putString("routing_backup", backup).commit()) {
                                throw new IllegalStateException("원래 통화 설정을 저장하지 못해 변경을 중단했습니다.");
                            }
                        } else if (stage.equals("on")) {
                            if (!preferences.edit().putBoolean("phone_mic_enabled", true).commit()) {
                                throw new IllegalStateException("변경 결과를 저장하지 못했습니다. 전원 버튼으로 원래 설정을 복원해 주세요.");
                            }
                        } else if (stage.equals("off")) {
                            if (!preferences.edit().putBoolean("phone_mic_enabled", false).remove("routing_backup").commit()) {
                                throw new IllegalStateException("통화 설정은 복원됐지만 저장 결과를 확인하지 못했습니다.");
                            }
                        } else if (stage.equals("status") && preferences.getBoolean("phone_mic_enabled", false)
                                && !reply.optBoolean("phoneMicPreferred")) {
                            preferences.edit().putBoolean("phone_mic_enabled", false).commit();
                        }
                    }
                } catch (Exception | LinkageError error) {
                    failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                } finally {
                    if (multicast != null && multicast.isHeld()) {
                        multicast.release();
                    }
                }
                String problem = failure;
                JSONObject result = reply;
                main.post(() -> {
                    if (closing || isDestroyed()) {
                        return;
                    }
                    busy = false;
                    if (problem != null) {
                        statusMessage = "처리 실패 · " + problem;
                        new AlertDialog.Builder(this).setTitle("설정하지 못했습니다")
                                .setMessage(problem)
                                .setPositiveButton("연결 설정", (dialog, which) -> setupButton.performClick())
                                .setNegativeButton("닫기", null).show();
                    } else if (action.equals("on")) {
                        int selectedInput = result == null ? -1 : result.optInt("selectedInputType", -1);
                        statusMessage = selectedInput == AudioDeviceInfo.TYPE_BUILTIN_MIC
                                ? "시스템이 휴대폰 마이크를 선택했습니다. 실제 통화에서 소리와 마이크를 확인해 주세요."
                                : "휴대폰 마이크 지정을 요청했습니다. 실제 통화에서 적용 여부를 확인해 주세요.";
                    } else if (action.equals("off")) {
                        statusMessage = "원래 통화 설정으로 복원했습니다.";
                    } else {
                        statusMessage = "시스템 연결과 오디오 제어 권한을 확인했습니다.";
                        diagnosticsButton.setTag(Boolean.TRUE);
                        diagnosticsButton.performClick();
                    }
                    preferences.edit().putString("last_result", statusMessage).apply();
                    refresh.run();
                });
            });
        };

        powerButton.setOnClickListener(view -> {
            if (!connected || busy) {
                return;
            }
            boolean restoring = preferences.getBoolean("phone_mic_enabled", false)
                    || preferences.contains("routing_backup");
            requestedAction = restoring ? "off" : "on";
            runCommand.run();
        });

        setupButton.setOnClickListener(view -> {
            if (busy) {
                return;
            }
            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add("android.permission.ACCESS_LOCAL_NETWORK");
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), 100);
                return;
            }
            ConnectivityManager networks = getSystemService(ConnectivityManager.class);
            boolean hasWifi = false;
            if (networks != null) {
                for (Network network : networks.getAllNetworks()) {
                    NetworkCapabilities capabilities = networks.getNetworkCapabilities(network);
                    if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        hasWifi = true;
                        break;
                    }
                }
            }
            if (!hasWifi) {
                new AlertDialog.Builder(this).setTitle("Wi-Fi 연결이 필요합니다")
                        .setMessage("무선 디버깅은 Wi-Fi에 연결되어 있어야 켤 수 있습니다.")
                        .setPositiveButton("Wi-Fi 설정", (dialog, which) -> startActivity(
                                new Intent(Settings.ACTION_WIFI_SETTINGS)))
                        .setNegativeButton("닫기", null).show();
                return;
            }
            if (preferences.getBoolean("adb_paired", false)) {
                new AlertDialog.Builder(this).setTitle("시스템 연결")
                        .setMessage("이미 페어링했다면 다시 연결을 누르세요. 휴대폰 설정에서 페어링을 삭제했다면 새로 연결하세요.")
                        .setPositiveButton("다시 연결", (dialog, which) -> {
                            requestedAction = "status";
                            runCommand.run();
                        }).setNeutralButton("새로 연결", (dialog, which) -> startActivity(
                                new Intent(this, PairingActivity.class)))
                        .setNegativeButton("닫기", null).show();
            } else {
                startActivity(new Intent(this, PairingActivity.class));
            }
        });

        diagnosticsButton.setOnClickListener(view -> {
            if (Boolean.TRUE.equals(diagnosticsButton.getTag())) {
                diagnosticsButton.setTag(null);
            } else if (preferences.getBoolean("adb_paired", false) && !busy) {
                requestedAction = "status";
                runCommand.run();
                return;
            }
            String message = "패키지: " + getPackageName() + "\n버전: 1.2 / 3"
                    + "\n기기: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nAndroid API: " + Build.VERSION.SDK_INT
                    + "\n이어폰 연결: " + connected
                    + "\n내장 디버깅 페어링: " + preferences.getBoolean("adb_paired", false)
                    + "\n설정: " + (preferences.getBoolean("phone_mic_enabled", false) ? "ON" : "OFF")
                    + "\n복원 정보: " + preferences.contains("routing_backup")
                    + "\n\n" + statusMessage + "\n\n"
                    + (preferences.contains("last_pair_error")
                    ? "페어링 오류: " + preferences.getString("last_pair_error", "")
                    + "\n접속 주소: " + preferences.getString("last_pair_endpoint", "") + "\n\n" : "")
                    + preferences.getString("last_diagnostics", "시스템 경로 확인 기록 없음");
            new AlertDialog.Builder(this).setTitle("연결 상태 · 오류")
                    .setMessage(message)
                    .setPositiveButton("복사", (dialog, which) -> {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        clipboard.setPrimaryClip(ClipData.newPlainText("OnlyListen 상태", message));
                    }).setNegativeButton("닫기", null).show();
        });
        findViewById(R.id.bluetooth_button).setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (ActivityNotFoundException error) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] devices) {
                refresh.run();
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                refresh.run();
            }
        };
        if (audio != null) {
            audio.registerAudioDeviceCallback(deviceCallback, main);
        }
        poll = new Runnable() {
            @Override
            public void run() {
                if (resumed && !closing) {
                    refresh.run();
                    main.postDelayed(this, 2000);
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        main.post(poll);
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 100) {
            boolean granted = results.length > 0;
            for (int result : results) {
                granted &= result == PackageManager.PERMISSION_GRANTED;
            }
            refresh.run();
            if (granted) {
                setupButton.performClick();
            } else {
                new AlertDialog.Builder(this).setTitle("권한이 필요합니다")
                        .setMessage("휴대폰 설정에서 OnlyListen의 주변 기기 권한을 허용해 주세요.")
                        .setPositiveButton("앱 설정", (dialog, which) -> startActivity(
                                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:" + getPackageName()))))
                        .setNegativeButton("닫기", null).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        closing = true;
        main.removeCallbacksAndMessages(null);
        if (audio != null && deviceCallback != null) {
            audio.unregisterAudioDeviceCallback(deviceCallback);
        }
        worker.execute(() -> {
            if (adb != null) {
                try {
                    adb.disconnect();
                } catch (Exception ignored) {
                }
            }
            timeouts.shutdown();
        });
        worker.shutdown();
        super.onDestroy();
    }
}
