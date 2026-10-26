package com.onlylisten.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
                        int port = preferences.getInt("adb_connect_port", -1);
                        if (port > 0) {
                            try {
                                adb.connect("127.0.0.1", port);
                            } catch (Exception ignored) {
                                adb.disconnect();
                            }
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
            int padding = Math.round(20 * getResources().getDisplayMetrics().density);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(padding, padding / 2, padding, 0);
            TextView instructions = new TextView(this);
            instructions.setText("별도 앱 설치 없이 이 휴대폰의 무선 디버깅에 연결합니다.\n\n"
                    + "1. 휴대폰을 Wi-Fi에 연결하고 개발자 옵션의 무선 디버깅을 켜 주세요.\n"
                    + "2. 설정과 OnlyListen을 분할 화면으로 열어 주세요.\n"
                    + "3. ‘페어링 코드로 기기 페어링’을 누르고 표시된 포트와 6자리 코드를 입력해 주세요.\n\n"
                    + "이미 페어링했다면 포트·코드를 비우고 다시 연결을 눌러도 됩니다. 연결 포트는 자동 탐색 실패 시에만 입력합니다.");
            content.addView(instructions);
            EditText pairPort = new EditText(this);
            pairPort.setHint("페어링 포트 · 예: 37123");
            pairPort.setInputType(InputType.TYPE_CLASS_NUMBER);
            pairPort.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
            content.addView(pairPort);
            EditText pairCode = new EditText(this);
            pairCode.setHint("6자리 페어링 코드");
            pairCode.setInputType(InputType.TYPE_CLASS_NUMBER);
            pairCode.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
            content.addView(pairCode);
            EditText connectPort = new EditText(this);
            connectPort.setHint("연결 포트 · 자동 탐색이 안 될 때만");
            connectPort.setInputType(InputType.TYPE_CLASS_NUMBER);
            connectPort.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5)});
            int savedPort = preferences.getInt("adb_connect_port", -1);
            if (savedPort > 0) {
                connectPort.setText(String.valueOf(savedPort));
            }
            content.addView(connectPort);
            ScrollView setupScroll = new ScrollView(this);
            setupScroll.addView(content, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
            AlertDialog setup = new AlertDialog.Builder(this)
                    .setTitle("최초 연결 설정").setView(setupScroll)
                    .setPositiveButton("연결", null)
                    .setNeutralButton("개발자 옵션", null)
                    .setNegativeButton("닫기", null).create();
            setup.setOnShowListener(ignored -> {
                setup.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                    } catch (ActivityNotFoundException error) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                });
                setup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                    String portText = pairPort.getText().toString().trim();
                    String code = pairCode.getText().toString().trim();
                    String connectText = connectPort.getText().toString().trim();
                    int pairingPort = -1;
                    int connectionPort = -1;
                    try {
                        if (!portText.isEmpty() || !code.isEmpty()) {
                            pairingPort = Integer.parseInt(portText);
                            if (pairingPort < 1 || pairingPort > 65535 || !code.matches("[0-9]{6}")) {
                                throw new IllegalArgumentException();
                            }
                        } else if (!preferences.getBoolean("adb_paired", false)) {
                            throw new IllegalArgumentException();
                        }
                        if (!connectText.isEmpty()) {
                            connectionPort = Integer.parseInt(connectText);
                            if (connectionPort < 1 || connectionPort > 65535) {
                                throw new IllegalArgumentException();
                            }
                        }
                    } catch (IllegalArgumentException error) {
                        pairPort.setError("페어링 화면의 포트와 6자리 코드를 확인해 주세요.");
                        return;
                    }
                    int requestedPairPort = pairingPort;
                    int requestedConnectPort = connectionPort;
                    setup.dismiss();
                    busy = true;
                    statusMessage = "이 휴대폰의 무선 디버깅에 연결하고 있습니다.";
                    refresh.run();
                    worker.execute(() -> {
                        String errorMessage = null;
                        try {
                            if (adb == null) {
                                adb = new WirelessAdbManager(getApplicationContext());
                            }
                            if (requestedPairPort > 0) {
                                if (!adb.pair("127.0.0.1", requestedPairPort, code)) {
                                    throw new IllegalStateException("페어링에 실패했습니다.");
                                }
                                if (!preferences.edit().putBoolean("adb_paired", true).commit()) {
                                    throw new IllegalStateException("페어링 결과를 저장하지 못했습니다.");
                                }
                            }
                            preferences.edit().putInt("adb_connect_port", requestedConnectPort).commit();
                        } catch (Exception | LinkageError error) {
                            errorMessage = error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage();
                        }
                        String error = errorMessage;
                        main.post(() -> {
                            if (closing || isDestroyed()) {
                                return;
                            }
                            busy = false;
                            if (error == null) {
                                requestedAction = "status";
                                runCommand.run();
                            } else {
                                statusMessage = "페어링 실패 · " + error;
                                refresh.run();
                                new AlertDialog.Builder(this).setTitle("연결 실패")
                                        .setMessage(error + "\n\n설정의 페어링 코드 화면을 열어 둔 상태에서 새 코드로 다시 연결해 주세요.")
                                        .setPositiveButton("확인", null).show();
                            }
                        });
                    });
                });
            });
            setup.show();
        });

        diagnosticsButton.setOnClickListener(view -> {
            if (Boolean.TRUE.equals(diagnosticsButton.getTag())) {
                diagnosticsButton.setTag(null);
            } else if (preferences.getBoolean("adb_paired", false) && !busy) {
                requestedAction = "status";
                runCommand.run();
                return;
            }
            String message = "패키지: " + getPackageName() + "\n버전: 1.1 / 2"
                    + "\n기기: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\nAndroid API: " + Build.VERSION.SDK_INT
                    + "\n이어폰 연결: " + connected
                    + "\n내장 디버깅 페어링: " + preferences.getBoolean("adb_paired", false)
                    + "\n설정: " + (preferences.getBoolean("phone_mic_enabled", false) ? "ON" : "OFF")
                    + "\n복원 정보: " + preferences.contains("routing_backup")
                    + "\n\n" + statusMessage + "\n\n"
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
