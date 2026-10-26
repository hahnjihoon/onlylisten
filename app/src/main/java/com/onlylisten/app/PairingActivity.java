package com.onlylisten.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PairingActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private EditText code;
    private EditText manualAddress;
    private TextView status;
    private Button settings;
    private Button connect;
    private String host;
    private String serviceName;
    private int port = -1;
    private boolean verified;
    private boolean registered;
    private boolean busy;
    private boolean closing;
    private boolean settingsOpened;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("bluetooth_policy", MODE_PRIVATE);
        getWindow().setDecorFitsSystemWindows(false);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);
        scroll.requestApplyInsets();

        TextView heading = new TextView(this);
        heading.setText("휴대폰 연결");
        heading.setTextSize(24);
        heading.setTextColor(getColor(R.color.text));
        content.addView(heading);
        TextView instructions = new TextView(this);
        instructions.setText("최초 연결과 제어 시작 때 Wi-Fi와 무선 디버깅이 필요합니다. 시작 후에는 휴대폰 내부 제어를 사용합니다.\n\n"
                + "1. 아래 버튼으로 설정을 앱 옆에 여세요.\n"
                + "2. 무선 디버깅 → 페어링 코드로 기기 페어링을 누르세요.\n"
                + "3. 설정의 코드 창을 유지한 채 이 앱에 숫자 6자리를 입력하고 연결을 누르세요.\n\n"
                + "코드와 페어링 포트는 창을 열 때마다 바뀝니다. 창을 닫으면 이전 번호는 사용할 수 없습니다.\n\n"
                + "최초 연결 때만 두 화면을 함께 사용합니다. 완료되면 앱의 전원 화면으로 돌아갑니다.");
        instructions.setTextColor(getColor(R.color.muted));
        instructions.setTextSize(14);
        instructions.setPadding(0, padding / 2, 0, padding / 2);
        content.addView(instructions);
        settings = new Button(this);
        settings.setText("설정을 앱 옆에 열기");
        content.addView(settings);
        status = new TextView(this);
        status.setText("설정의 페어링 코드 창을 열면 현재 포트를 자동으로 찾습니다.");
        status.setTextColor(getColor(R.color.accent));
        status.setPadding(0, padding / 2, 0, padding / 2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        code = new EditText(this);
        code.setHint("설정에 표시된 6자리 코드");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setSingleLine(true);
        code.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        content.addView(code);
        connect = new Button(this);
        connect.setText("연결");
        content.addView(connect);
        Button manual = new Button(this);
        manual.setText("자동 탐색이 안 되면 주소 입력");
        content.addView(manual);
        manualAddress = new EditText(this);
        manualAddress.setHint("페어링 코드 창의 IP 주소 및 포트");
        manualAddress.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        manualAddress.setSingleLine(true);
        manualAddress.setVisibility(View.GONE);
        content.addView(manualAddress);
        Button back = new Button(this);
        back.setText("전원 화면으로 돌아가기");
        back.setOnClickListener(view -> {
            if (!busy) {
                finish();
            }
        });
        content.addView(back);
        manual.setOnClickListener(view -> {
            if (!busy) {
                manualAddress.setVisibility(manualAddress.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        if (state != null) {
            code.setText(state.getString("code", ""));
            manualAddress.setText(state.getString("address", ""));
            manualAddress.setVisibility(state.getBoolean("manual") ? View.VISIBLE : View.GONE);
            host = state.getString("host");
            port = state.getInt("port", -1);
            settingsOpened = state.getBoolean("settings_opened");
        }

        settings.setOnClickListener(view -> {
            if (busy) {
                return;
            }
            Intent target = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Settings.Global.getInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0) {
                new AlertDialog.Builder(this).setTitle("개발자 옵션을 먼저 켜 주세요")
                        .setMessage("설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드번호를 7번 누르세요. 완료한 뒤 이 앱에서 설정 열기를 다시 누르세요.")
                        .setPositiveButton("휴대전화 정보", (dialog, which) -> startActivity(
                                new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(
                                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK)))
                        .setNegativeButton("닫기", null).show();
                return;
            }
            try {
                startActivity(target);
                settingsOpened = true;
                status.setText("설정에서 무선 디버깅을 켜고 페어링 코드 창을 여세요. 설정과 앱이 함께 보여야 합니다.");
            } catch (ActivityNotFoundException error) {
                status.setText("휴대폰 설정에서 개발자 옵션 → 무선 디버깅을 열어 주세요. 설정과 OnlyListen을 분할 화면으로 유지해 주세요.");
            }
        });

        connect.setOnClickListener(view -> {
            if (busy) {
                return;
            }
            if (!isInMultiWindowMode()) {
                status.setText("설정의 코드 창이 닫히면 연결할 수 없습니다. ‘설정을 앱 옆에 열기’를 눌러 두 화면을 함께 유지해 주세요.");
                return;
            }
            String enteredCode = code.getText().toString().trim();
            if (!enteredCode.matches("[0-9]{6}")) {
                code.setError("현재 페어링 창의 숫자 6자리를 입력해 주세요.");
                return;
            }
            String pairingHost = host;
            int pairingPort = port;
            String address = manualAddress.getVisibility() == View.VISIBLE
                    ? manualAddress.getText().toString().trim() : "";
            if (!address.isEmpty()) {
                try {
                    int separator = address.lastIndexOf(':');
                    pairingHost = address.substring(0, separator).trim();
                    pairingPort = Integer.parseInt(address.substring(separator + 1).trim());
                    if (!pairingHost.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                            || pairingPort < 1 || pairingPort > 65535) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
                    manualAddress.setError("페어링 코드 창의 IP 주소와 포트를 콜론(:)까지 그대로 입력해 주세요.");
                    return;
                }
            } else if (!verified || pairingHost == null || pairingPort < 1) {
                status.setText("현재 페어링 포트를 찾지 못했습니다. 설정의 코드 창을 유지하거나 주소 직접 입력을 사용해 주세요.");
                return;
            }
            String requestedHost = pairingHost;
            int requestedPort = pairingPort;
            busy = true;
            code.setEnabled(false);
            manualAddress.setEnabled(false);
            settings.setEnabled(false);
            connect.setEnabled(false);
            status.setText("연결 중입니다. 설정의 페어링 코드 창을 닫지 말아 주세요.");
            worker.execute(() -> {
                String failure = null;
                try {
                    InetAddress local = InetAddress.getByName(requestedHost);
                    if (NetworkInterface.getByInetAddress(local) == null) {
                        throw new IllegalArgumentException("이 휴대폰의 페어링 주소가 아닙니다.");
                    }
                    WirelessAdbManager manager = new WirelessAdbManager(getApplicationContext());
                    if (!manager.pair(requestedHost, requestedPort, enteredCode)) {
                        throw new IllegalStateException("페어링에 실패했습니다.");
                    }
                    if (!preferences.edit().putBoolean("adb_paired", true)
                            .putBoolean("adb_pairing_completed", true)
                            .putString("adb_pairing_result", "페어링을 완료했습니다. 시스템 연결을 확인합니다.")
                            .remove("adb_connect_port").remove("last_pair_error").commit()) {
                        throw new IllegalStateException("페어링 결과를 저장하지 못했습니다.");
                    }
                } catch (Exception | LinkageError error) {
                    failure = error.toString();
                    preferences.edit().putString("last_pair_error", failure)
                            .putString("last_pair_endpoint", requestedHost + ":" + requestedPort).apply();
                }
                String error = failure;
                main.post(() -> {
                    if (closing) {
                        return;
                    }
                    busy = false;
                    if (error == null) {
                        finish();
                    } else {
                        code.setEnabled(true);
                        manualAddress.setEnabled(true);
                        settings.setEnabled(true);
                        connect.setEnabled(true);
                        status.setText(error.contains("ECONNREFUSED")
                                ? "페어링 창이 닫혔거나 포트가 바뀌어 연결되지 않았습니다. 설정에서 코드 창을 다시 열고 현재 번호를 입력해 주세요."
                                : "페어링하지 못했습니다. 설정과 앱을 함께 유지한 채 현재 코드와 주소를 확인해 주세요.");
                        preferences.edit().putString("last_result", status.getText().toString())
                                .putString("adb_pairing_result", status.getText().toString()).apply();
                    }
                });
            });
        });
        code.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                connect.performClick();
                return true;
            }
            return false;
        });

        nsd = getSystemService(NsdManager.class);
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
                main.post(() -> {
                    registered = true;
                    if (closing) {
                        try {
                            nsd.stopServiceDiscovery(this);
                        } catch (RuntimeException ignored) {
                        }
                    }
                });
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (closing) {
                    return;
                }
                try {
                    nsd.resolveService(service, new NsdManager.ResolveListener() {
                        @Override
                        public void onServiceResolved(NsdServiceInfo resolved) {
                            main.post(() -> {
                                if (closing) {
                                    return;
                                }
                                try {
                                    List<InetAddress> addresses = Build.VERSION.SDK_INT >= 34
                                            ? resolved.getHostAddresses() : Collections.singletonList(resolved.getHost());
                                    InetAddress selected = null;
                                    for (InetAddress candidate : addresses) {
                                        if (candidate != null && NetworkInterface.getByInetAddress(candidate) != null) {
                                            selected = candidate;
                                            if (candidate instanceof Inet4Address) {
                                                break;
                                            }
                                        }
                                    }
                                    if (selected == null || resolved.getPort() < 1) {
                                        return;
                                    }
                                    String currentHost = selected.getHostAddress();
                                    if (port > 0 && (port != resolved.getPort()
                                            || !java.util.Objects.equals(host, currentHost)) && !busy) {
                                        code.setText("");
                                        if (manualAddress.getVisibility() == View.VISIBLE) {
                                            manualAddress.setText(currentHost + ":" + resolved.getPort());
                                        }
                                    }
                                    host = currentHost;
                                    port = resolved.getPort();
                                    serviceName = resolved.getServiceName();
                                    verified = true;
                                    if (!busy) {
                                        status.setText("현재 페어링 포트를 찾았습니다. 설정의 숫자 6자리를 이 앱에 입력하고 연결을 누르세요.");
                                    }
                                } catch (Exception error) {
                                    preferences.edit().putString("last_pair_error", error.toString()).apply();
                                    status.setText("자동 탐색에 실패했습니다. 페어링 코드 창의 주소를 직접 입력해 주세요.");
                                }
                            });
                        }

                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            preferences.edit().putString("last_pair_error", "페어링 주소 탐색 실패: " + errorCode).apply();
                            main.post(() -> {
                                if (!closing && !busy && !verified) {
                                    status.setText("자동 탐색에 실패했습니다. 페어링 코드 창의 주소를 직접 입력해 주세요.");
                                }
                            });
                        }
                    });
                } catch (RuntimeException error) {
                    preferences.edit().putString("last_pair_error", error.toString()).apply();
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                main.post(() -> {
                    if (!closing && service.getServiceName().equals(serviceName)) {
                        verified = false;
                        port = -1;
                        host = null;
                        serviceName = null;
                        if (!busy) {
                            code.setText("");
                            manualAddress.setText("");
                            status.setText("페어링 창이 닫혀 이전 번호가 만료됐습니다. 설정과 앱을 함께 유지하고 새 코드 창을 여세요.");
                        }
                    }
                });
            }

            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                preferences.edit().putString("last_pair_error", "페어링 검색 시작 실패: " + errorCode).apply();
                main.post(() -> {
                    if (!closing) {
                        status.setText("자동 탐색을 시작하지 못했습니다. 페어링 코드 창의 주소를 직접 입력해 주세요.");
                    }
                });
            }

            @Override
            public void onDiscoveryStopped(String type) {
                registered = false;
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
            }
        };
        try {
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("onlylisten-pairing");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
            nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException error) {
            status.setText("자동 탐색을 시작하지 못했습니다. 페어링 코드 창의 주소를 직접 입력해 주세요.");
            preferences.edit().putString("last_pair_error", error.toString()).apply();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferences.getBoolean("adb_pairing_completed", false)) {
            finish();
        } else if (settingsOpened && !isInMultiWindowMode() && !busy) {
            verified = false;
            code.setText("");
            manualAddress.setText("");
            status.setText("전체 화면으로 앱에 돌아오면 설정의 페어링 창이 닫힙니다. ‘설정을 앱 옆에 열기’로 다시 시작해 주세요.");
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindow, Configuration configuration) {
        super.onMultiWindowModeChanged(inMultiWindow, configuration);
        if (!inMultiWindow && settingsOpened && !busy) {
            verified = false;
            code.setText("");
            manualAddress.setText("");
            status.setText("설정과 앱을 함께 유지해야 현재 코드로 연결할 수 있습니다. 설정을 앱 옆에 다시 열어 주세요.");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("code", code.getText().toString());
        state.putString("address", manualAddress.getText().toString());
        state.putBoolean("manual", manualAddress.getVisibility() == View.VISIBLE);
        state.putString("host", host);
        state.putInt("port", port);
        state.putBoolean("settings_opened", settingsOpened);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        closing = true;
        if (registered) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        }
        if (multicast != null && multicast.isHeld()) {
            multicast.release();
        }
        worker.shutdown();
        super.onDestroy();
    }
}
